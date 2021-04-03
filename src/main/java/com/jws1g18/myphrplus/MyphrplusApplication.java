package com.jws1g18.myphrplus;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import com.jws1g18.myphrplus.DTOS.User;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.client.utils.Idn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import co.junwei.bswabe.BswabeMsk;
import co.junwei.bswabe.BswabePrv;
import co.junwei.bswabe.BswabePub;
import co.junwei.bswabe.SerializeUtils;

@SpringBootApplication
@RestController
public class MyphrplusApplication {
	Logger logger = LoggerFactory.getLogger(MyphrplusApplication.class);

	Helpers helper = new Helpers();
	GCPFireBase fireBase = new GCPFireBase(logger, helper);
	GCPCloudStorage cloudStorage = new GCPCloudStorage(logger);

	public static void main(String[] args) {
		SpringApplication.run(MyphrplusApplication.class);
	}

	/**
	 * Adds a new patient to the Firestore
	 * 
	 * @param patient JSON containing patient information
	 * @return Returns HTTP response code and message
	 */
	@RequestMapping(method = RequestMethod.POST, path = "/registerPatient")
	public ResponseEntity<?> registerPatient(@RequestBody User patient) {
		logger.info("Incoming request to register Patient: " + patient.email);
		// Check authentication
		FunctionResponse authResponse = fireBase.verifyUidToken(patient.uid);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		// Check NHS Num
		if(!fireBase.checkNHSnum(patient.nhsnum)){
			return new ResponseEntity<>("NHS Number has already been registered", HttpStatus.BAD_REQUEST);
		}


		// Create Attribute array
		ArrayList<String> attributes = new ArrayList<>();
		attributes.add("uid_" + authResponse.getMessage());
		attributes.add("Patient");
		attributes.add("nhsNum_" + patient.nhsnum);

		// Add patient to firestore
		FunctionResponse addResponse = fireBase.addPatient(authResponse.getMessage(), patient, attributes);
		if (!addResponse.successful()) {
			return new ResponseEntity<>(addResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}

		FunctionResponse keyResponse = helper.genAndStorePrivKeys(addResponse.getMessage(),
				attributes.toArray(new String[0]), authResponse.getMessage());
		if (keyResponse.successful()) {
			logger.info("Patient successfully created: " + patient.email);
			return new ResponseEntity<>("Patient created successfully", HttpStatus.OK);
		}
		return new ResponseEntity<>("Patient creation failed", HttpStatus.BAD_REQUEST);
	}

	/**
	 * Gets user information
	 * 
	 * @param uid Firebase auth user id token
	 * @return Returns HTTP response code and user information
	 */
	@RequestMapping(method = RequestMethod.GET, path = "/getUser")
	public ResponseEntity<?> getUser(@RequestHeader("Xx-Firebase-Id-Token") String uidToken) {
		// Check authentication
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request to get User info for user: " + authResponse.getMessage());
		// Get user information
		FunctionResponse getResponse = fireBase.getUser(authResponse.getMessage());
		if (getResponse.successful()) {
			logger.info("User info request for user: "+ authResponse.getMessage() + " was successfull");
			return new ResponseEntity<>(getResponse.getMessage(), HttpStatus.OK);
		} else {
			return new ResponseEntity<>(getResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Deletes a user from the firebase and firebase auth
	 * 
	 * @param uidToken Firebase ID token
	 * @return
	 */
	@RequestMapping(method = RequestMethod.GET, path = "/deleteUser")
	public ResponseEntity<?> deleteUser(@RequestHeader("Xx-Firebase-Id-Token") String uidToken) {
		// Check authentication
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request to delete user: " + authResponse.getMessage());
		// Get user object
		User user;
		try {
			user = fireBase.getUserObject(authResponse.getMessage());
		} catch (InterruptedException | ExecutionException e) {
			logger.error("Couldn't get user object", e);
			return new ResponseEntity<>("Couldn't get user object", HttpStatus.BAD_REQUEST);
		}

		if (user.role.equals("DP") || user.role.equals("admin")) {
			return new ResponseEntity<>("Cannot delete account due to role. Contact System Admin", HttpStatus.BAD_REQUEST);
		}

		// Delete files
		for(String fileRef: user.files){
			FunctionResponse deleteResponse = fireBase.deleteFile(authResponse.getMessage(), fileRef);
			if(deleteResponse.successful() && !deleteResponse.getMessage().equals("No delete needed")){
				Boolean delete = cloudStorage.deleteFile(user.bucketName, deleteResponse.getMessage());
				if(delete){
					logger.info("File: " + fileRef + " deleted from cloud storage during account deletion");
				}
				else{
					return new ResponseEntity<>("Error occured whilst deleting your files", HttpStatus.BAD_REQUEST);
				}
			} else if(!deleteResponse.successful()) {
				return new ResponseEntity<>(deleteResponse.getMessage(), HttpStatus.BAD_REQUEST);
			}
		}

		// Delete user
		FunctionResponse deleteResponse = fireBase.deleteUser(authResponse.getMessage());
		if (deleteResponse.successful()) {
			logger.info("User: " + authResponse.getMessage() + " successfully deleted");
			return new ResponseEntity<>(deleteResponse.getMessage(), HttpStatus.OK);
		} else {
			return new ResponseEntity<>(deleteResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	/***
	 * Uploads a file to the cloud storage
	 * 
	 * @param uidToken
	 * @param file
	 * @return
	 */
	@RequestMapping(value = "/uploadFilePatient", method = RequestMethod.POST, consumes = { "multipart/form-data" })
	public ResponseEntity<?> uploadFilePatient(@RequestHeader("Xx-Firebase-Id-Token") String uidToken,
			@RequestParam(name = "file") MultipartFile file, @RequestParam(name = "name") String name,
			@RequestParam(name = "customAccessPolicy") String customAccessPolicy,
			@RequestParam(name = "accessPolicy") ArrayList<String> accessPolicy) {
		// Check auth token
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from user: " + authResponse.getMessage() + " to upload file with name: " + name);

		// Check user role
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && roleCheck.getMessage().equals("Patient")) {
			// Get Patient object
			User user;
			try {
				user = fireBase.getUserObject(authResponse.getMessage());
			} catch (InterruptedException | ExecutionException ex) {
				logger.error("Get patient failed", ex);
				return new ResponseEntity<>("Get patient object failed", HttpStatus.BAD_REQUEST);
			}

			ArrayList<String> uids = fireBase.getUids(accessPolicy, customAccessPolicy, authResponse.getMessage(),
					user);
			String accessPolicyStr = helper.parsePatientPolicy(accessPolicy, customAccessPolicy, uids);

			return uploadFile(authResponse.getMessage(), file, accessPolicyStr, name, uids);

		} else if (roleCheck.successful() && !roleCheck.getMessage().equals("Patient")) {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>(roleCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Handles uploading a file for DRs and DPs
	 */
	@RequestMapping(value = "/uploadFile", method = RequestMethod.POST, consumes = { "multipart/form-data" })
	public ResponseEntity<?> uploadFile(@RequestHeader("Xx-Firebase-Id-Token") String uidToken,
		@RequestParam(name = "file") MultipartFile file, @RequestParam(name = "name") String name,
		@RequestParam(name = "accessPolicy") String accessPolicy,
		@RequestParam(name = "users") List<String> users){
		// Check auth token
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from user: " + authResponse.getMessage() + " to upload file with name: " + name);
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && (roleCheck.getMessage().equals("DR") || roleCheck.getMessage().equals("DP"))) {
			//Get patients
			ArrayList<String> uids = new ArrayList<>();
			if(roleCheck.getMessage().equals("DR")){
				uids.add(authResponse.getMessage());
				for(String user: users){
					QuerySnapshot qs;
					try {
						qs = fireBase.queryUsers("nhsnum", user);
					} catch (InterruptedException | ExecutionException e) {
						logger.error("Could not get user: " + user, e);
						return new ResponseEntity<>("Could not find user with NHS num: " + user, HttpStatus.BAD_REQUEST);
					}
					for(QueryDocumentSnapshot doc: qs.getDocuments()){
						uids.add(doc.getId());
					}
				}
			} else if (roleCheck.getMessage().equals("DP")){
				uids = new ArrayList<>(users);
			}

			return uploadFile(authResponse.getMessage(), file, accessPolicy, name, uids);
		} else {
			return new ResponseEntity<>("You do not have correct permissions", HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Encrypts and Uploads a file to cloud storage, adds reference to firestore 
	 */
	private ResponseEntity<?> uploadFile(String uid, MultipartFile file, String accessPolicy, String fileName, ArrayList<String> uids){
		// Get User object
		User user;
		try {
			user = fireBase.getUserObject(uid);
		} catch (InterruptedException | ExecutionException ex) {
			logger.error("Get user failed", ex);
			return new ResponseEntity<>("Get user object failed", HttpStatus.BAD_REQUEST);
		}
		
		// Check file type + extension
		FunctionResponse typeCheck = helper.detectFileType(file);
		if (!typeCheck.successful()) {
			return new ResponseEntity<>(typeCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
		String extension = typeCheck.getMessage().split(" ")[0];
		String type = typeCheck.getMessage().split(" ")[1];

		// Encrypt File
		byte[] pubByte;
		try {
			pubByte = GCPSecretManager.getKeys(user.bucketName + "-public");
		} catch (IOException e) {
			logger.error("Could not get public key for: " + user.bucketName, e);
			return new ResponseEntity<>("Could not get public key", HttpStatus.BAD_REQUEST);
		}
		BswabePub pub = SerializeUtils.unserializeBswabePub(pubByte);
		byte[] encFile;
		try {
			encFile = ABE.encrypt(pub, accessPolicy, file.getBytes());
		} catch (Exception e) {
			logger.error("Could not encrypt file", e);
			return new ResponseEntity<>("Could not encrypt file", HttpStatus.BAD_REQUEST);
		}

		// Upload object
		String filepath = user.parent + "/" + uid + "/" + RandomStringUtils.random(20);
		FunctionResponse uploadResponse = cloudStorage.uploadFile(user.bucketName, filepath, encFile, type);
		if (!uploadResponse.successful()) {
			return new ResponseEntity<>(uploadResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}

		// Add refrence to firestore
		Map<String, Object> fileInfo = new HashMap<>();
		fileInfo.put("filepath", filepath);
		fileInfo.put("type", type);
		fileInfo.put("extension", extension);
		fileInfo.put("uploader", uid);
		fileInfo.put("fileName", fileName);

		String fileRef;
		try {
			fileRef = fireBase.addFile(fileInfo);
		} catch (InterruptedException | ExecutionException ex) {
			logger.error("Adding file reference to firestore failed", ex);
			return new ResponseEntity<>("Adding file reference to firestore failed", HttpStatus.BAD_REQUEST);
		}


		for (String uidTemp : uids) {
			try {
				fireBase.updateArray(uidTemp, "files", fileRef);
			} catch (InterruptedException | ExecutionException ex) {
				logger.error("Adding file reference to user " + uidTemp + " failed", ex);
			}
		}

		logger.info("File upload for user:" + uid + " successful");
		return new ResponseEntity<>("File Upload Successful", HttpStatus.OK);
	}

	/**
	 * Deletes a file either from a users fileRefs or if last user with file Ref then deletes whole file
	 * @param uidToken firebase user ID token
	 * @param fileRef File reference 
	 * @return
	 */
	@RequestMapping(value = "/deleteFile", method = RequestMethod.POST)
	public ResponseEntity<?> deleteFile(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestParam(name = "fileRef") String fileRef){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from user: " + authResponse.getMessage() + " to delete file: " + fileRef);
		FunctionResponse deleteResponse = fireBase.deleteFile(authResponse.getMessage(), fileRef);
		if(deleteResponse.successful()){
			if(deleteResponse.getMessage().equals("No delete needed")){
				logger.info("Request to delete file: " + fileRef + " successful");
				return new ResponseEntity<>("Delete successfull", HttpStatus.OK);
			}
			else{
				User user;
				try {
					user = fireBase.getUserObject(authResponse.getMessage());
				} catch (InterruptedException | ExecutionException e) {
					logger.error("Couldn't get user:"+ authResponse.getMessage() + " object", e);
					return new ResponseEntity<>("Failed to get user object", HttpStatus.BAD_REQUEST);
				}
				Boolean delete = cloudStorage.deleteFile(user.bucketName, deleteResponse.getMessage());
				if(delete){
					logger.info("Request to delete file: " + fileRef + " successful, file deleted from cloud storage");
					return new ResponseEntity<>("Delete successfull", HttpStatus.OK);
				}
				return new ResponseEntity<>("Delete unsuccessfull", HttpStatus.BAD_REQUEST);
			}
		} else {
			return new ResponseEntity<>(deleteResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}
		
	/**
	 * Returns a users role
	 * 
	 * @param uidToken
	 * @return
	 */
	@RequestMapping(value = "/getUserRole", method = RequestMethod.GET)
	public ResponseEntity<?> getUserRole(@RequestHeader("Xx-Firebase-Id-Token") String uidToken) {
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		FunctionResponse getResponse = fireBase.getRole(authResponse.getMessage());
		if (getResponse.successful()) {
			return new ResponseEntity<>(getResponse.getMessage(), HttpStatus.OK);
		} else {
			return new ResponseEntity<>(getResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Creates a new Data Provider account, bucket, and private and master keys
	 * @param uidToken
	 * @param user
	 * @return
	 */
	@RequestMapping(value = "/newDP", method = RequestMethod.POST)
	public ResponseEntity<?> newDP(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestBody User user) {
		logger.info("Incoming request to add DP with name:" +user.name);
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		// Check user is admin
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && roleCheck.getMessage().equals("admin")) {
			UserRecord userRecord;
			try {
				userRecord = fireBase.registerUser(user.name, user.email, user.password);
			} catch (FirebaseAuthException ex) {
				logger.error("Add DP failed", ex);
				return new ResponseEntity<>("Creating user failed", HttpStatus.BAD_REQUEST);
			}
			// Create bucket for DP
			String bucketName = cloudStorage.createBucket(user.name.replace(" ", "-").toLowerCase());

			// Generate keys for hierarchy
			Object[] setup = ABE.setup();
			BswabePub pub = (BswabePub) setup[0];
			BswabeMsk msk = (BswabeMsk) setup[1];

			// Store Public keys
			try {
				GCPSecretManager.storeKey(bucketName + "-public", SerializeUtils.serializeBswabePub(pub));
			} catch (IOException ex) {
				return new ResponseEntity<>("Public key could not be stored", HttpStatus.BAD_REQUEST);
			}
			// Store Master key
			try {
				GCPSecretManager.storeKey(bucketName + "-master", SerializeUtils.serializeBswabeMsk(msk));
			} catch (IOException ex) {
				return new ResponseEntity<>("Master key could not be stored", HttpStatus.BAD_REQUEST);
			}

			// Create Attribute Array
			ArrayList<String> attributes = new ArrayList<>();
			attributes.add("uid_" + userRecord.getUid());
			attributes.add("DP");

			// Create DP profile on firebase
			FunctionResponse addResponse = fireBase.addDP(user, bucketName, attributes, userRecord);
			if (!addResponse.successful()) {
				return new ResponseEntity<>(addResponse.getMessage(), HttpStatus.BAD_REQUEST);
			}

			FunctionResponse keyResponse = helper.genAndStorePrivKeys(bucketName, attributes.toArray(new String[0]),
					userRecord.getUid());
			if (keyResponse.successful()) {
				logger.info("DP: " + user.name + " created successfully");
				return new ResponseEntity<>("DP creation successful", HttpStatus.OK);
			}
			return new ResponseEntity<>("Failed to create DP", HttpStatus.BAD_REQUEST);

		} else if (roleCheck.successful() && !roleCheck.getMessage().equals("admin")) {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>(roleCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Adds a new Data Requester to the firestore, also generates a private key and stores in secret manager
	 * @param uidToken
	 * @param user
	 * @return
	 */
	@RequestMapping(value = "/newDR", method = RequestMethod.POST)
	public ResponseEntity<?> newDR(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestBody User user) {
		logger.info("Incoming request to add DR with name" + user.name);
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		// Check user is DP
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && roleCheck.getMessage().equals("DP")) {
			// Make user with firebase
			UserRecord userRecord;
			try {
				userRecord = fireBase.registerUser(user.name, user.email, user.password);
			} catch (FirebaseAuthException ex) {
				return new ResponseEntity<>("Creating user failed", HttpStatus.BAD_REQUEST);
			}

			// Create Attribute Array
			ArrayList<String> attributes = new ArrayList<>();
			attributes.add("uid_" + userRecord.getUid());
			attributes.add("DR");

			FunctionResponse addResponse = fireBase.addDR(user, authResponse.getMessage(), attributes, userRecord);
			if (!addResponse.successful()) {
				return new ResponseEntity<>(addResponse.getMessage(), HttpStatus.BAD_REQUEST);
			}

			FunctionResponse keyResponse = helper.genAndStorePrivKeys(addResponse.getMessage(),
					attributes.toArray(new String[0]), userRecord.getUid());
			if (keyResponse.successful()) {
				logger.info("DR: " +user.name + " successfully created");
				return new ResponseEntity<>("DR successfully created", HttpStatus.OK);
			}
			return new ResponseEntity<>("DR Creation failed", HttpStatus.BAD_REQUEST);

		} else if (roleCheck.successful() && !roleCheck.getMessage().equals("DP")) {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>(roleCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Returns a list of a users files
	 * @param uidToken
	 * @return
	 */
	@RequestMapping(value = "/getFiles", method = RequestMethod.GET)
	public ResponseEntity<?> getFiles(@RequestHeader("Xx-Firebase-Id-Token") String uidToken) {
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		FunctionResponse fileResponse = fireBase.getFiles(authResponse.getMessage());
		if (fileResponse.successful()) {
			return new ResponseEntity<>(fileResponse.getMessage(), HttpStatus.OK);
		} else {
			return new ResponseEntity<>(fileResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Downloads a file from cloud storage, attempts to decrypt and returns the file if successful 
	 * @param uidToken
	 * @param fileRef
	 * @return
	 */
	@RequestMapping(value = "/downloadFile", method = RequestMethod.POST)
	public ResponseEntity<?> downloadFile(@RequestHeader("Xx-Firebase-Id-Token") String uidToken,
			@RequestParam("fileRef") String fileRef) {
		// Check auth
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from: " + authResponse.getMessage() + " to download file " + fileRef);
		// Get file path + type
		FunctionResponse fileResponse = fireBase.getFilePath(fileRef);
		if (!fileResponse.successful()) {
			return new ResponseEntity<>(fileResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		String filePath = fileResponse.getMessage().split(",")[0];
		String fileType = fileResponse.getMessage().split(",")[1];

		User user;
		try {
			user = fireBase.getUserObject(authResponse.getMessage());
		} catch (InterruptedException | ExecutionException ex) {
			return new ResponseEntity<>("Couldn't get user object", HttpStatus.BAD_REQUEST);
		}

		String bucketName = user.bucketName;

		ByteArrayResource file = cloudStorage.downloadObject(bucketName, filePath);

		byte[] pubByte;
		byte[] prvByte;
		try {
			pubByte = GCPSecretManager.getKeys(bucketName + "-public");
			prvByte = GCPSecretManager.getKeys(authResponse.getMessage());
		} catch (IOException ex) {
			return new ResponseEntity<>("Couldn't retrive keys", HttpStatus.BAD_REQUEST);
		}
		BswabePub pub = SerializeUtils.unserializeBswabePub(pubByte);
		BswabePrv prv = SerializeUtils.unserializeBswabePrv(pub, prvByte);
		byte[] decFile;
		try {
			decFile = ABE.decrypt(pub, prv, file.getInputStream());
		} catch (Exception e) {
			logger.error("File couldn't be decrytped", e);
			return new ResponseEntity<>("File could not be decrypted, May not have correct attributes",
					HttpStatus.BAD_REQUEST);
		}
		ByteArrayResource resFile = new ByteArrayResource(decFile);
		logger.info("Request to download file: " + fileRef + " was successful");
		return ResponseEntity.ok().contentLength(resFile.contentLength())
				.contentType(MediaType.parseMediaType(fileType)).body(resFile);
	}

	/**
	 * Returns a list of all current data providers, for use in registration 
	 * @return
	 */
	@RequestMapping(value="/getAllDPs", method=RequestMethod.GET)
	public ResponseEntity<?> getAllDPs() {
		FunctionResponse dpResponse = fireBase.getAllDPs();
		if(dpResponse.successful()){
			return new ResponseEntity<>(dpResponse.getMessage(), HttpStatus.OK);
		}
		return new ResponseEntity<>(dpResponse.getMessage(), HttpStatus.BAD_REQUEST);
	}
	
	/**
	 * Returns a list of a DR's patients
	 * @param uidToken
	 * @return
	 */
	@RequestMapping(value="/getPatients", method=RequestMethod.GET)
	public ResponseEntity<?> getPatients(@RequestHeader("Xx-Firebase-Id-Token") String uidToken){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from user: " + authResponse.getMessage() + " to get list of patients");
		FunctionResponse pResponse = fireBase.getAllPatients(authResponse.getMessage());
		if(pResponse.successful()){
			logger.info("Get patients request from " + authResponse.getMessage() + " successfull");
			return new ResponseEntity<>(pResponse.getMessage(), HttpStatus.OK);
		}
		return new ResponseEntity<>(pResponse.getMessage(), HttpStatus.BAD_REQUEST);
	}

	/**
	 * Returns a list of patient files that are accessable to the DR that requests
	 * @param uidToken
	 * @param nhsNum
	 * @return
	 */
	@RequestMapping(value="/getPatientFiles", method=RequestMethod.POST)
	public ResponseEntity<?> getPatientFiles(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestParam("nhsNum") String nhsNum){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from user: " +authResponse.getMessage() + " to get patient: " +nhsNum + " (nhsnum) files");
		// Check user is DR
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && roleCheck.getMessage().equals("DR")) {
			//Get patient files
			FunctionResponse fileResponse = fireBase.getPatientFiles(nhsNum, authResponse.getMessage());
			if(fileResponse.successful()){
				logger.info("Request to get user: " + nhsNum + " files successful");
				return new ResponseEntity<>(fileResponse.getMessage(), HttpStatus.OK);
			}
			else{
				return new ResponseEntity<>(fileResponse.getMessage(), HttpStatus.BAD_REQUEST);
			}
		} else if (roleCheck.successful() && !roleCheck.getMessage().equals("DP")) {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>(roleCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Returns a list of user attributes from a UID
	 * @param uidToken
	 * @param uid
	 * @return
	 */
	@RequestMapping(value="/getUserAttributes", method = RequestMethod.POST)
	public ResponseEntity<?> getUserAttributes(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestParam("identifier") String identifier){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from: " + authResponse.getMessage() + " to get a user: " + identifier + " attributes ");
		// Check user is DP
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && roleCheck.getMessage().equals("DP")) {
			FunctionResponse attrResponse = fireBase.getUserAttributes(identifier);
			if(attrResponse.successful()){
				logger.info("Request from : " + authResponse.getMessage() + " to get user attributes successful");
				return new ResponseEntity<>(attrResponse.getMessage(), HttpStatus.OK);
			}
			return new ResponseEntity<>(attrResponse.getMessage(), HttpStatus.BAD_REQUEST);
		} else if (roleCheck.successful() && roleCheck.getMessage().equals("DR")) {
			FunctionResponse attrResponse = fireBase.getPatientAttributes(identifier);
			if(attrResponse.successful()){
				logger.info("Request from: " + authResponse.getMessage() + "to get patient attributes successful");
				return new ResponseEntity<>(attrResponse.getMessage(), HttpStatus.OK);
			}else{
				return new ResponseEntity<>(attrResponse.getMessage(), HttpStatus.BAD_REQUEST);
			}
		}else if (roleCheck.successful() && (!roleCheck.getMessage().equals("DP") || !roleCheck.getMessage().equals("DR"))) {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>(roleCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	@RequestMapping(value="/addUserAttribute", method=RequestMethod.POST)
	public ResponseEntity<?> addUserAttribute(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestParam("identifier") String identifier, @RequestParam("attribute") String attribute){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from: " + authResponse.getMessage() + " to add attribute " + attribute + " to user: " +identifier);
		if(helper.validateNewAttribute(attribute)){
			logger.error("Invalid attribute entered");
			return new ResponseEntity<>("Invalid attribute entered", HttpStatus.BAD_REQUEST);
		}
		// Check user is DP
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && roleCheck.getMessage().equals("DP")) {
			FunctionResponse addResponse = fireBase.updateUserAttributes(identifier, attribute);
			if(addResponse.successful()){
				logger.info("Request from: " + authResponse.getMessage() + " to add attribute successful");
				return new ResponseEntity<>("Add successful", HttpStatus.OK);
			}
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		} else // Check user is DR
		if (roleCheck.successful() && roleCheck.getMessage().equals("DR")) {
			FunctionResponse addResponse = fireBase.updatePatientAttributes(identifier, attribute);
			if(addResponse.successful()){
				logger.info("Add attribute request from: " + authResponse.getMessage() + " successfull");
				return new ResponseEntity<>(addResponse.getMessage(), HttpStatus.OK);
			}
			else{
				return new ResponseEntity<>(addResponse.getMessage(), HttpStatus.BAD_REQUEST);
			}
		} else if (roleCheck.successful() && (!roleCheck.getMessage().equals("DP") || !roleCheck.getMessage().equals("DR"))) {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>(roleCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	@RequestMapping(value="/removeUserAttribute", method=RequestMethod.POST)
	public ResponseEntity<?> removeUserAttribute(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestParam("identifier") String identifier, @RequestParam("attribute") String attribute){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from: " + authResponse.getMessage() + " to remove attribute " + attribute + " to user: " + identifier);
		if(helper.validateRemoveAttribute(attribute)){
			logger.error("Attribute not allowed to be deleted");
			return new ResponseEntity<>("Cannot delete this attribute", HttpStatus.BAD_REQUEST);
		}
		// Check user is DP
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && roleCheck.getMessage().equals("DP")) {
			FunctionResponse delResponse = fireBase.removeUserAttribute(identifier, attribute);
			if(delResponse.successful()){
				logger.info("Remove attribute request from: " + authResponse.getMessage() + " successfull");
				return new ResponseEntity<>("Delete successful", HttpStatus.OK);
			}
			return new ResponseEntity<>(delResponse.getMessage(), HttpStatus.BAD_REQUEST);
		//Check if user is DR
		} else if (roleCheck.successful() && roleCheck.getMessage().equals("DR")) {
			FunctionResponse removeResponse = fireBase.removePatientAttribute(identifier, attribute);
			if(removeResponse.successful()){
				logger.info("Remove attribute request from: " + authResponse.getMessage() + " successfull");
				return new ResponseEntity<>(removeResponse.getMessage(), HttpStatus.OK);
			}
			else{
				return new ResponseEntity<>(removeResponse.getMessage(), HttpStatus.BAD_REQUEST);
			}
		} else if (roleCheck.successful() && (!roleCheck.getMessage().equals("DP") || !roleCheck.getMessage().equals("DR"))) {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>(roleCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Returns a JSON of all users in the request DPs bucket, for use when uploading files
	 * @param uidToken 
	 * @return
	 */
	@RequestMapping(value="/getAllInBucket", method=RequestMethod.POST)
	public ResponseEntity<?> getAllInBucket(@RequestHeader("Xx-Firebase-Id-Token") String uidToken){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from: " + authResponse.getMessage() + " to get all users in bucket");
		//Check role
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && roleCheck.getMessage().equals("DP")) {
			FunctionResponse getResponse = fireBase.getAllInBucket(authResponse.getMessage());
			if(getResponse.successful()){
				logger.info("Request from " + authResponse.getMessage() + " to get all users in bucket successful");
				return new ResponseEntity<>(getResponse.getMessage(), HttpStatus.OK);
			}
			else{
				return new ResponseEntity<>(getResponse.getMessage(), HttpStatus.BAD_REQUEST);
			}

		} else if (roleCheck.successful() && !roleCheck.getMessage().equals("DP")) {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>(roleCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Returns a users reminders, with optional param NHSNUM if request is sent via DP
	 * @param uidToken
	 * @param nhsnum
	 * @return
	 */
	@RequestMapping(value="/getReminders", method=RequestMethod.POST)
	public ResponseEntity<?> getReminders(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestParam("nhsNum") String nhsnum){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from: " + authResponse.getMessage() + " to get reminders ");
		//Check role
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && roleCheck.getMessage().equals("DR")) {
			FunctionResponse reminderResponse = fireBase.getPatientReminders(nhsnum);
			if(reminderResponse.successful()){
				logger.info("Get reminder request successful");
				return new ResponseEntity<>(reminderResponse.getMessage(), HttpStatus.OK);
			}
			return new ResponseEntity<>(reminderResponse.getMessage(), HttpStatus.BAD_REQUEST);
		} else if (roleCheck.successful() && roleCheck.getMessage().equals("Patient")) {
			FunctionResponse reminderResponse = fireBase.getReminders(authResponse.getMessage());
			if(reminderResponse.successful()){
				logger.info("Get reminder request successful");
				return new ResponseEntity<>(reminderResponse.getMessage(), HttpStatus.OK);
			}
			return new ResponseEntity<>(reminderResponse.getMessage(), HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Adds a reminder to a users firestore
	 * @param uidToken Token of DR making request
	 * @param nhsnum NHS num of patient to add too
	 * @param reminder Reminder too add
	 * @return
	 */
	@RequestMapping(value="/addReminder", method=RequestMethod.POST)
	public ResponseEntity<?> addReminder(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestParam("nhsNum") String nhsnum, @RequestParam("reminder") String reminder){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from: " + authResponse.getMessage() + " to add reminder: " + reminder + " to: " + nhsnum);
		//Check role
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && roleCheck.getMessage().equals("DR")) {
			FunctionResponse addResponse = fireBase.addReminder(nhsnum, reminder);
			if(addResponse.successful()){
				logger.info("Add request from: " + authResponse.getMessage() + " was successful");
				return new ResponseEntity<>(addResponse.getMessage(), HttpStatus.OK);
			}
			return new ResponseEntity<>(addResponse.getMessage(), HttpStatus.BAD_REQUEST);

		}else if (roleCheck.successful() && !roleCheck.getMessage().equals("DR")) {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>(roleCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	@RequestMapping(value="/removeReminder", method=RequestMethod.POST)
	public ResponseEntity<?> removeReminder(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestParam("reminder") String reminder){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from: " + authResponse.getMessage() + " to remove reminder: " + reminder);
		FunctionResponse removeResponse = fireBase.removeReminder(authResponse.getMessage(), reminder);
		if(removeResponse.successful()){
			logger.info("Reminder successfully removed");
			return new ResponseEntity<>("Delete successful", HttpStatus.OK);
		}
		return new ResponseEntity<>("Delete failed", HttpStatus.BAD_REQUEST);
	}

	/**
	 * Gets the information to be displayed on the dashboard in JSON form
	 * @param uidToken 
	 * @return
	 */
	@RequestMapping(value="/getDashboardInfo", method=RequestMethod.GET)
	public ResponseEntity<?> getDashboardInfo(@RequestHeader("Xx-Firebase-Id-Token") String uidToken){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from: " + authResponse.getMessage() + " to get dashboard information");
		User user;
        try {
            user = fireBase.getUserObject(authResponse.getMessage());
        } catch (InterruptedException | ExecutionException e) {
            logger.error("Could not get user", e);
            return new ResponseEntity<>("Could not get user", HttpStatus.BAD_REQUEST);
        }

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode objectNode = mapper.createObjectNode();
        if(user.role.equals("Patient")){
			try {
				objectNode.put("DR", fireBase.getDB().collection("users").document(user.parent).get().get().getString("name"));
			} catch (InterruptedException | ExecutionException e) {
				logger.error("Couldn't get parent: " + user.parent);
			}
			
            ArrayNode arrayNode = mapper.createArrayNode();
            for(String rem: user.reminders){
                arrayNode.addObject().put("reminder", rem);
            }
            objectNode.set("items", arrayNode);
        } else if(user.role.equals("DR")){
            String hospital = user.bucketName.replace("-myphrplus-backend", "");
            objectNode.put("hospital", hospital.replace("-", " "));
            objectNode.put("patients", user.patients.size());
        } else if(user.role.equals("DP")){
            objectNode.put("DRs", user.dataRequesters.size());
        } else if(user.role.equals("admin")){

        } else{
            return new ResponseEntity<>("Invalid role, contact System Admin", HttpStatus.BAD_REQUEST);
        }
        try {
			logger.info("Dashboard information request from: " + authResponse.getMessage() + " successful");
            return new ResponseEntity<>(mapper.writer().writeValueAsString(objectNode), HttpStatus.OK);
        } catch (JsonProcessingException e) {
            logger.error("JSON proccessing error");
            return new ResponseEntity<>("Could not process JSON", HttpStatus.BAD_REQUEST);
        }
	}

	/**
	 * Uploads a new diary to cloud storage, saving a reference to firestore.
	 * Diary is encrypted between patient and doctor
	 * @param uidToken 
	 * @param diaryContent
	 * @return
	 */
	@RequestMapping(value="/uploadDiary", method=RequestMethod.POST)
	public ResponseEntity<?> uploadDiary(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestParam("name") String diaryTitle, @RequestParam("content") String diaryContent){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from: " + authResponse.getMessage() + " to add diary with name: " + diaryTitle);
		//Check role
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && roleCheck.getMessage().equals("Patient")) {
			String cleanContent = helper.cleanHtml(diaryContent);
			String diaryDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(LocalDateTime.now()); //Get current time in nice format

			User user;
			try {
				user = fireBase.getUserObject(authResponse.getMessage());
			} catch (InterruptedException | ExecutionException ex) {
				return new ResponseEntity<>("Couldn't get user object", HttpStatus.BAD_REQUEST);
			}

			String accessPolicy = "uid_" + authResponse.getMessage() + " uid_" + user.parent + " 1of2";

			// Encrypt Diary
			byte[] pubByte;
			try {
				pubByte = GCPSecretManager.getKeys(user.bucketName + "-public");
			} catch (IOException e) {
				logger.error("Could not get public key for: " + user.bucketName, e);
				return new ResponseEntity<>("Could not get public key", HttpStatus.BAD_REQUEST);
			}
			BswabePub pub = SerializeUtils.unserializeBswabePub(pubByte);
			byte[] encFile;
			try {
				encFile = ABE.encrypt(pub, accessPolicy, cleanContent.getBytes());
			} catch (Exception e) {
				logger.error("Could not encrypt diary", e);
				return new ResponseEntity<>("Could not encrypt diary", HttpStatus.BAD_REQUEST);
			}

			// Upload object
			String filepath = user.parent + "/" + authResponse.getMessage() + "/diaries/" + RandomStringUtils.random(20);
			FunctionResponse uploadResponse = cloudStorage.uploadFile(user.bucketName, filepath, encFile, "text/html");
			if (!uploadResponse.successful()) {
				return new ResponseEntity<>(uploadResponse.getMessage(), HttpStatus.BAD_REQUEST);
			}

			// Add refrence to firestore
			Map<String, Object> diaryInfo = new HashMap<>();
			diaryInfo.put("name", diaryTitle);
			diaryInfo.put("date", diaryDate);
			diaryInfo.put("ref", filepath);
			FunctionResponse addResponse = fireBase.addDiaryRef(authResponse.getMessage(), diaryInfo);
			if(addResponse.successful()){
				logger.info("Add diary request from: " + authResponse.getMessage() + " successful");
				return new ResponseEntity<>(addResponse.getMessage(), HttpStatus.OK);
			}
			return new ResponseEntity<>(addResponse.getMessage(), HttpStatus.BAD_REQUEST);

		} else if (roleCheck.successful() && !roleCheck.getMessage().equals("Patient")) {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>(roleCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Returns a list of a patients diary entries in JSON form
	 * @param uidToken
	 * @param identifier
	 * @return
	 */
	@RequestMapping(value="/getDiaries", method=RequestMethod.POST)
	public ResponseEntity<?> getDiaries(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestParam(name="identifier", required = false) String identifier){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from: " + authResponse.getMessage() + " to add get diaries list " + identifier);
		//Check role
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && roleCheck.getMessage().equals("Patient")) {
			FunctionResponse getResponse = fireBase.getDiaries(authResponse.getMessage());
			if(getResponse.successful()){
				return new ResponseEntity<>(getResponse.getMessage(), HttpStatus.OK);
			}
			return new ResponseEntity<>(getResponse.getMessage(), HttpStatus.BAD_REQUEST);
		} else if (roleCheck.successful() && roleCheck.getMessage().equals("DR")){
			String uid = fireBase.getUIDfromNHSnum(identifier);
			FunctionResponse getResponse = fireBase.getDiaries(uid);
			if(getResponse.successful()){
				logger.info("Get diaries request from: " + authResponse.getMessage() + " successful");
				return new ResponseEntity<>(getResponse.getMessage(), HttpStatus.OK);
			}
			return new ResponseEntity<>(getResponse.getMessage(), HttpStatus.BAD_REQUEST);
		} else if (roleCheck.successful() && (!roleCheck.getMessage().equals("Patient") || !roleCheck.getMessage().equals("DR"))) {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>(roleCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Returns the HTML content of the requested diary 
	 * @param uidToken 
	 * @param diaryRef
	 * @param identifier
	 * @return
	 */
	@RequestMapping(value="/getDiary", method=RequestMethod.POST)
	public ResponseEntity<?> getDiary(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestParam("ref") String diaryRef, @RequestParam(name="identifier", required = false) String identifier){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from: " + authResponse.getMessage() + " to add get diary :" + identifier + "/" + diaryRef);
		//Check role
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && (roleCheck.getMessage().equals("Patient") || roleCheck.getMessage().equals("DR"))) {
			String location = null;
			if(roleCheck.getMessage().equals("Patient")){
				FunctionResponse getResponse = fireBase.getDiaryFilelocation(diaryRef, authResponse.getMessage());
				if(!getResponse.successful()){
					return new ResponseEntity<>(getResponse.getMessage(), HttpStatus.BAD_REQUEST);
				}
				location = getResponse.getMessage();
			} else if (roleCheck.getMessage().equals("DR")){
				String uid = fireBase.getUIDfromNHSnum(identifier);
				FunctionResponse getResponse = fireBase.getDiaryFilelocation(diaryRef, uid);
				if(!getResponse.successful()){
					return new ResponseEntity<>(getResponse.getMessage(), HttpStatus.BAD_REQUEST);
				}
				location = getResponse.getMessage();
			}
			User user;
			try {
				user = fireBase.getUserObject(authResponse.getMessage());
			} catch (InterruptedException | ExecutionException ex) {
				return new ResponseEntity<>("Couldn't get user object", HttpStatus.BAD_REQUEST);
			}

			ByteArrayResource file = cloudStorage.downloadObject(user.bucketName, location);

			byte[] pubByte;
			byte[] prvByte;
			try {
				pubByte = GCPSecretManager.getKeys(user.bucketName + "-public");
				prvByte = GCPSecretManager.getKeys(authResponse.getMessage());
			} catch (IOException ex) {
				return new ResponseEntity<>("Couldn't retrive keys", HttpStatus.BAD_REQUEST);
			}
			BswabePub pub = SerializeUtils.unserializeBswabePub(pubByte);
			BswabePrv prv = SerializeUtils.unserializeBswabePrv(pub, prvByte);
			byte[] decFile;
			try {
				decFile = ABE.decrypt(pub, prv, file.getInputStream());
			} catch (Exception e) {
				logger.error("File couldn't be decrytped", e);
				return new ResponseEntity<>("File could not be decrypted, May not have correct attributes",
						HttpStatus.BAD_REQUEST);
			}

			String html = new String(decFile, StandardCharsets.UTF_8);
			return new ResponseEntity<>(html, HttpStatus.OK);
		} else if (roleCheck.successful() && (!roleCheck.getMessage().equals("Patient") || !roleCheck.getMessage().equals("DR"))) {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>(roleCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}

	/**
	 * Deletes a diary from firestore and cloud storage
	 * @param uidToken
	 * @param diaryRef
	 * @param identifier
	 * @return
	 */
	@RequestMapping(value="/deleteDiary", method=RequestMethod.POST)
	public ResponseEntity<?> deleteDiary(@RequestHeader("Xx-Firebase-Id-Token") String uidToken, @RequestParam("ref") String diaryRef){
		FunctionResponse authResponse = fireBase.verifyUidToken(uidToken);
		if (!authResponse.successful()) {
			return new ResponseEntity<>(authResponse.getMessage(), HttpStatus.BAD_REQUEST);
		}
		logger.info("Authenticated request from: " + authResponse.getMessage() + " to add get diary: " + diaryRef);
		//Check role
		FunctionResponse roleCheck = fireBase.getRole(authResponse.getMessage());
		if (roleCheck.successful() && (roleCheck.getMessage().equals("Patient"))){
			FunctionResponse getLocResponse = fireBase.getDiaryFilelocation(diaryRef, authResponse.getMessage());
			if(!getLocResponse.successful()){
				return new ResponseEntity<>(getLocResponse.getMessage(), HttpStatus.BAD_REQUEST);
			}
			FunctionResponse deleteFBResponse = fireBase.deleteDiary(authResponse.getMessage(), diaryRef);
			if(!deleteFBResponse.successful()){
				return new ResponseEntity<>(deleteFBResponse.getMessage(), HttpStatus.BAD_REQUEST);
			}
			User user;
			try {
				user = fireBase.getUserObject(authResponse.getMessage());
			} catch (InterruptedException | ExecutionException e) {
				logger.error("Couldn't get user:"+ authResponse.getMessage() + " object", e);
				return new ResponseEntity<>("Failed to get user object", HttpStatus.BAD_REQUEST);
			}
			Boolean delete = cloudStorage.deleteFile(user.bucketName, getLocResponse.getMessage());
			if(delete){
				logger.info("Request to delete diary: " + diaryRef + " successful, diary deleted from cloud storage");
				return new ResponseEntity<>("Delete successfull", HttpStatus.OK);
			}
			return new ResponseEntity<>("Delete unsuccessfull", HttpStatus.BAD_REQUEST);
		} else if (roleCheck.successful() && !roleCheck.getMessage().equals("Patient")) {
			return new ResponseEntity<>("You do not have the correct permissions", HttpStatus.BAD_REQUEST);
		} else {
			return new ResponseEntity<>(roleCheck.getMessage(), HttpStatus.BAD_REQUEST);
		}
	}
}