package org.ihtsdo.rvf.execution.service.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Resource;
import javax.naming.ConfigurationException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.ihtsdo.otf.dao.s3.S3Client;
import org.ihtsdo.otf.dao.s3.helper.FileHelper;
import org.ihtsdo.otf.rest.exception.BusinessServiceException;
import org.ihtsdo.rvf.entity.Assertion;
import org.ihtsdo.rvf.entity.AssertionGroup;
import org.ihtsdo.rvf.entity.TestRunItem;
import org.ihtsdo.rvf.entity.TestType;
import org.ihtsdo.rvf.entity.ValidationReport;
import org.ihtsdo.rvf.execution.service.AssertionExecutionService;
import org.ihtsdo.rvf.execution.service.ReleaseDataManager;
import org.ihtsdo.rvf.execution.service.ResourceDataLoader;
import org.ihtsdo.rvf.execution.service.impl.ValidationReportService.State;
import org.ihtsdo.rvf.service.AssertionService;
import org.ihtsdo.rvf.validation.StructuralTestRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
public class ValidationRunner {
	
	private static final String SEPARATOR = "/";

	private static final String INTERNATIONAL = "international";

	private static final String ZIP_FILE_EXTENSION = ".zip";

	public static final String FAILURE_MESSAGE = "failureMessage";

	private final Logger logger = LoggerFactory.getLogger(ValidationRunner.class);
	
	@Autowired
	private StructuralTestRunner structuralTestRunner;
	
	@Autowired
	private ReleaseDataManager releaseDataManager;
	
	@Autowired
	private AssertionService assertionService;
	
	@Autowired
	private AssertionExecutionService assertionExecutionService;
	
	@Autowired
	private ResourceDataLoader resourceLoader;
	
	@Autowired
	private RvfDbScheduledEventGenerator scheduleEventGenerator;
	
	@Resource
	private S3Client s3Client;
	
	private int batchSize = 0;

	private ExecutorService executorService = Executors.newCachedThreadPool();
	@Autowired
	private ValidationReportService reportService;
	
	public ValidationRunner( int batchSize) {
		this.batchSize = batchSize;
	}
	
	public void run(ValidationRunConfig validationConfig) {
		final Map<String , Object> responseMap = new LinkedHashMap<>();
		try {
			responseMap.put("validationConfig", validationConfig);
			runValidation(responseMap, validationConfig);
		} catch (final Throwable t) {
			final StringWriter errors = new StringWriter();
			t.printStackTrace(new PrintWriter(errors));
			final String failureMsg = "System Failure: " + t.getMessage() + " : " + errors.toString();
			responseMap.put(FAILURE_MESSAGE, failureMsg);
			logger.error("Exception thrown, writing as result",t);
			try {
				reportService.writeResults(responseMap, State.FAILED, validationConfig.getStorageLocation());
			} catch (final Exception e) {
				//Can't even record the error to disk!  Lets hope Telemetry is working
				logger.error("Failed to record failure (which was: " + failureMsg + ") due to " + e.getMessage());
			}
		} finally {
			FileUtils.deleteQuietly(validationConfig.getLocalProspectiveFile());
			FileUtils.deleteQuietly(validationConfig.getLocalManifestFile());
		}
	}

	private void runValidation(final Map<String , Object> responseMap, ValidationRunConfig validationConfig) throws Exception {
		
		final Calendar startTime = Calendar.getInstance();
		logger.info(String.format("Started execution with runId [%1s] : ", validationConfig.getRunId()));
		// load the filename
		final String structureTestStartMsg = "Start structure testing for release file:" + validationConfig.getTestFileName();
		logger.info(structureTestStartMsg);
		String reportStorage = validationConfig.getStorageLocation();
		reportService.writeProgress(structureTestStartMsg, reportStorage);
		reportService.writeState(State.RUNNING, reportStorage);
		if (validationConfig.isProspectiveFilesInS3()) {
			//streaming file from S3 to local
			long s3StreamingStart = System.currentTimeMillis();
			FileHelper s3Helper = new FileHelper(validationConfig.getS3ExecutionBucketName(), s3Client);
			InputStream input = s3Helper.getFileStream(validationConfig.getProspectiveFileFullPath());
			File prospectiveFile = File.createTempFile(validationConfig.getRunId() + "_" + validationConfig.getTestFileName(), null);
			IOUtils.copy(input, new FileOutputStream(prospectiveFile));
			validationConfig.setLocalProspectiveFile(prospectiveFile);
			if (validationConfig.getManifestFileFullPath() != null) {
				InputStream manifestInput = s3Helper.getFileStream(validationConfig.getManifestFileFullPath());
				File manifestFile = File.createTempFile("manifest.xml_" + validationConfig.getRunId(), null);
				IOUtils.copy(manifestInput, new FileOutputStream(manifestFile));
				validationConfig.setLocalManifestFile(manifestFile);
			}
			logger.info("Time taken {} seconds to download files {} from s3", (System.currentTimeMillis()-s3StreamingStart)/1000 , validationConfig.getProspectiveFileFullPath());
		} else {
			validationConfig.setLocalProspectiveFile(new File(validationConfig.getProspectiveFileFullPath()));
			if (validationConfig.getManifestFileFullPath() != null) {
				validationConfig.setLocalManifestFile(new File(validationConfig.getManifestFileFullPath()));
			}
		}
		boolean isFailed = structuralTestRunner.verifyZipFileStructure(responseMap, validationConfig.getLocalProspectiveFile(), validationConfig.getRunId(), 
				validationConfig.getLocalManifestFile(), validationConfig.isWriteSucceses(), validationConfig.getUrl());
		reportService.putFileIntoS3(reportStorage, new File(structuralTestRunner.getStructureTestReportFullPath()));
		if (isFailed) {
			reportService.writeResults(responseMap, State.FAILED, reportStorage);
			return;
		} 
		ExecutionConfig executionConfig = loadPublishedAndProspectiveVersions(validationConfig, responseMap, reportStorage);
		if (executionConfig != null) {
			runAssertionTests(executionConfig,responseMap, reportStorage);
			final Calendar endTime = Calendar.getInstance();
			final long timeTaken = (endTime.getTimeInMillis() - startTime.getTimeInMillis()) / 60000;
			logger.info(String.format("Finished execution with runId : [%1s] in [%2s] minutes ", validationConfig.getRunId(), timeTaken));
			responseMap.put("startTime", startTime.getTime());
			responseMap.put("endTime", endTime.getTime());
			reportService.writeResults(responseMap, State.COMPLETE, reportStorage);
			//house keeping prospective version and combined previous extension 
			scheduleEventGenerator.createDropReleaseSchemaEvent(releaseDataManager.getSchemaForRelease(executionConfig.getProspectiveVersion()));
			releaseDataManager.dropVersion(executionConfig.getProspectiveVersion());
			if (executionConfig.isExtensionValidation()) {
				scheduleEventGenerator.createDropReleaseSchemaEvent(releaseDataManager.getSchemaForRelease(executionConfig.getPreviousVersion()));
				releaseDataManager.dropVersion(executionConfig.getPreviousVersion());
			}
			// house keeping qa_result for the given run id
			scheduleEventGenerator.createQaResultDeleteEvent(executionConfig.getExecutionId());
		}
		
	}

	private ExecutionConfig loadPublishedAndProspectiveVersions(ValidationRunConfig validationConfig, Map<String, Object> responseMap, String reportStorage) throws Exception {
		
		String prospectiveVersion = validationConfig.getRunId().toString();
		String prevReleaseVersion = resolvePreviousVersion(validationConfig.getPrevIntReleaseVersion());
		final boolean isExtension = isExtension(validationConfig);
		if (isExtension) {
			prevReleaseVersion = "previous_" + validationConfig.getRunId();
		}
		final ExecutionConfig executionConfig = new ExecutionConfig(validationConfig.getRunId(), validationConfig.isFirstTimeRelease());
		executionConfig.setProspectiveVersion(prospectiveVersion);
		executionConfig.setPreviousVersion(prevReleaseVersion);
		executionConfig.setGroupNames(validationConfig.getGroupsList());
		executionConfig.setExtensionValidation(isExtension);
		//default to 10
		executionConfig.setFailureExportMax(10);
		if (validationConfig.getFailureExportMax() != null) {
			executionConfig.setFailureExportMax(validationConfig.getFailureExportMax());
		}
		List<String> rf2FilesLoaded = new ArrayList<>();
		boolean isSucessful = false;
		if (!isPublishedVersionsLoaded(validationConfig)) {
			//load published versions from s3 and load prospective file
			isSucessful = prepareVersionsFromS3Files(validationConfig, reportStorage,responseMap, rf2FilesLoaded, executionConfig);
			
		} else {
			isSucessful = combineKnownReleases(validationConfig, reportStorage,responseMap, rf2FilesLoaded, executionConfig);
		}
		if (!isSucessful) {
			return null;
		}
		responseMap.put("totalRF2FilesLoaded", rf2FilesLoaded.size());
		Collections.sort(rf2FilesLoaded);
		responseMap.put("rf2Files", rf2FilesLoaded);

		final String prospectiveSchema = releaseDataManager.getSchemaForRelease(prospectiveVersion);
		if (prospectiveSchema != null) {
			reportService.writeProgress("Loading resource data for prospective schema:" + prospectiveSchema, reportStorage);
			resourceLoader.loadResourceData(prospectiveSchema);
			logger.info("completed loading resource data for schema:" + prospectiveSchema);
		}
		return executionConfig;
	}
	
	private boolean combineKnownReleases(ValidationRunConfig validationConfig, String reportStorage, Map<String, Object> responseMap, List<String> rf2FilesLoaded, ExecutionConfig executionConfig) throws Exception{
		boolean isFailed = checkKnownVersion(validationConfig,responseMap);
		if (isFailed) {
			reportService.writeResults(responseMap, State.FAILED, reportStorage);
			return false;
		}
		if (isExtension(validationConfig) && !validationConfig.isFirstTimeRelease()) {
			//SnomedCT_Release-es_INT_20140430.zip
			//SnomedCT_SpanishRelease_INT_20141031.zip
			if (validationConfig.getPrevIntReleaseVersion() != null) {
				String combinedVersionName = executionConfig.getPreviousVersion();
				final String startCombiningMsg = String.format("Combining previous releases:[%s],[%s] into: [%s]", validationConfig.getPrevIntReleaseVersion() , validationConfig.getPreviousExtVersion(), combinedVersionName);
				logger.info(startCombiningMsg);
				reportService.writeProgress(startCombiningMsg, reportStorage);
				final boolean isSuccess = releaseDataManager.combineKnownVersions(combinedVersionName, validationConfig.getPrevIntReleaseVersion(), validationConfig.getPreviousExtVersion());
				if (!isSuccess) {
					String message = "Failed to combine known versions:" 
							+ validationConfig.getPrevIntReleaseVersion() + " and " + validationConfig.getPreviousExtVersion() + " into " + combinedVersionName;
					responseMap.put(FAILURE_MESSAGE, message);
					reportService.writeResults(responseMap, State.FAILED, validationConfig.getStorageLocation());
					String schemaName = releaseDataManager.getSchemaForRelease(combinedVersionName);
					if (schemaName != null) {
						scheduleEventGenerator.createDropReleaseSchemaEvent(schemaName);
						releaseDataManager.dropVersion(combinedVersionName);
					}
					return false;
				}
			}
		} 
		reportService.writeProgress("Loading prospective file into DB.", reportStorage);
		String prospectiveVersion = validationConfig.getRunId().toString();
		if (isExtension(validationConfig)) {
			uploadProspectiveVersion(prospectiveVersion, validationConfig.getExtensionDependencyVersion(), validationConfig.getLocalProspectiveFile(), rf2FilesLoaded);
		} else if (validationConfig.isRf2DeltaOnly()) {
			 ProspectiveReleaseDataLoader loader = new ProspectiveReleaseDataLoader(validationConfig, releaseDataManager);
			 rf2FilesLoaded = loader.loadProspectiveDeltaWithPreviousSnapshotIntoDB(prospectiveVersion);
		} else {		  			
			uploadProspectiveVersion(prospectiveVersion, null, validationConfig.getLocalProspectiveFile(), rf2FilesLoaded);
		}
		return true;
	}

	private String resolvePreviousVersion(String releasePkgName) {
		String version = releasePkgName;
		if (releasePkgName.endsWith(ZIP_FILE_EXTENSION)) {
			version = releasePkgName.replace(ZIP_FILE_EXTENSION, "");
			String [] splits = version.split("_");
			if (splits.length >=4) {
				version = splits[2] + "_" + splits[3];
			}
		}
		return version;
	}
	
	private boolean prepareVersionsFromS3Files(ValidationRunConfig validationConfig, String reportStorage, Map<String, Object> responseMap,List<String> rf2FilesLoaded, ExecutionConfig executionConfig) throws Exception {
		FileHelper s3PublishFileHelper = new FileHelper(validationConfig.getS3PublishBucketName(), s3Client);
		if (validationConfig.getPrevIntReleaseVersion() != null) {
			String previousPublished = INTERNATIONAL + SEPARATOR + validationConfig.getPrevIntReleaseVersion();
			logger.debug("download published version from s3:" + previousPublished );
			InputStream previousIntInput = s3PublishFileHelper.getFileStream(previousPublished);
			File previousVersionTemp = File.createTempFile(validationConfig.getPrevIntReleaseVersion(), null);
			IOUtils.copy(previousIntInput, new FileOutputStream(previousVersionTemp));
			List<String> prevRf2FilesLoaded = new ArrayList<>();
			if (isExtension(validationConfig) && !validationConfig.isFirstTimeRelease()) {
				String combinedVersionName = executionConfig.getPreviousVersion();
				final String startCombiningMsg = String.format("Combining previous releases:[%s],[%s] into [%s]", validationConfig.getPrevIntReleaseVersion() , validationConfig.getPreviousExtVersion(), combinedVersionName);
				logger.info(startCombiningMsg);
				reportService.writeProgress(startCombiningMsg, reportStorage);
				String previousExtZipFile = INTERNATIONAL + SEPARATOR + validationConfig.getPreviousExtVersion();
				logger.debug("downloading published extension from s3:" + previousExtZipFile);
				InputStream previousExtInput = s3PublishFileHelper.getFileStream(previousExtZipFile);
				File previousExtTemp = File.createTempFile(validationConfig.getPreviousExtVersion(), null);
				IOUtils.copy(previousExtInput, new FileOutputStream(previousExtTemp));
				
				releaseDataManager.loadSnomedData(combinedVersionName, prevRf2FilesLoaded, previousVersionTemp,previousExtTemp);
				String schemaName = releaseDataManager.getSchemaForRelease(executionConfig.getPreviousVersion());
				if (schemaName == null) {
					responseMap.put(FAILURE_MESSAGE, "Failed to load two versions:" 
							+ validationConfig.getPrevIntReleaseVersion() + " and " + validationConfig.getPreviousExtVersion() + " into " + combinedVersionName);
					reportService.writeResults(responseMap, State.FAILED, validationConfig.getStorageLocation());
					return false;
				}
			} else {
				releaseDataManager.loadSnomedData(executionConfig.getPreviousVersion(), prevRf2FilesLoaded, previousVersionTemp);
			}
		} 
		
		String prospectiveVersion = validationConfig.getRunId().toString();
		if (isExtension(validationConfig)) {
			String extensionDependency = INTERNATIONAL + SEPARATOR + validationConfig.getExtensionDependencyVersion();
			logger.debug("download published  extension dependency version from s3:" +  extensionDependency);
			InputStream extensionDependencyInput = s3PublishFileHelper.getFileStream(extensionDependency);
			File extensionDependencyTemp = File.createTempFile(validationConfig.getExtensionDependencyVersion(), null);
			IOUtils.copy(extensionDependencyInput, new FileOutputStream(extensionDependencyTemp));
			releaseDataManager.loadSnomedData(prospectiveVersion, rf2FilesLoaded, validationConfig.getLocalProspectiveFile(),extensionDependencyTemp);
		} else {
			uploadProspectiveVersion(prospectiveVersion, null, validationConfig.getLocalProspectiveFile(), rf2FilesLoaded);
		}
		return true;
	}

	private boolean isPublishedVersionsLoaded(ValidationRunConfig validationConfig) {
		if (validationConfig.getPrevIntReleaseVersion() != null && validationConfig.getPrevIntReleaseVersion().endsWith(ZIP_FILE_EXTENSION)) {
			return false;
		}
		if (validationConfig.getPreviousExtVersion() != null && validationConfig.getPreviousExtVersion().endsWith(ZIP_FILE_EXTENSION)) {
			return false;
		}
		if (validationConfig.getExtensionDependencyVersion() != null && validationConfig.getExtensionDependencyVersion().endsWith(ZIP_FILE_EXTENSION)) {
			return false;
		}
		return true;
	}

	private boolean checkKnownVersion(ValidationRunConfig validationConfig, Map<String, Object> responseMap) {
		logger.debug("Checking known versions...");
		String previousExtVersion =validationConfig.getPreviousExtVersion();
		String extensionBaseLine = validationConfig.getExtensionDependencyVersion();
		String prevIntReleaseVersion = validationConfig.getPrevIntReleaseVersion();
		if (previousExtVersion != null) {
			if (extensionBaseLine == null) {
				responseMap.put(FAILURE_MESSAGE, "PreviousExtensionVersion is :" 
						+ prevIntReleaseVersion + " but extension release base line has not been specified.");
				return true;
			}
		}
		if (!validationConfig.isFirstTimeRelease() && prevIntReleaseVersion == null && previousExtVersion == null && extensionBaseLine == null) {
			responseMap.put(FAILURE_MESSAGE, "None of the known release version is specified");
			return true;
		}
		boolean isFailed = false;
		if (prevIntReleaseVersion != null && !prevIntReleaseVersion.isEmpty()) {
			if (!isKnownVersion(prevIntReleaseVersion, responseMap)) {
				isFailed = true;
			}
		}
		if (previousExtVersion != null && !previousExtVersion.isEmpty()) {
			if (!isKnownVersion(previousExtVersion, responseMap)) {
				isFailed = true;
			}
		}
		if (extensionBaseLine != null && !extensionBaseLine.isEmpty()) {
			if (!isKnownVersion(extensionBaseLine, responseMap)) {
				isFailed = true;
			}
		}
		return isFailed;
	}

	private boolean isExtension(final ValidationRunConfig runConfig) {
		return (runConfig.getExtensionDependencyVersion() != null 
				&& !runConfig.getExtensionDependencyVersion().trim().isEmpty()) ? true : false;
	}
	
	/*private void combineKnownVersions(final String combinedVersion, final String firstKnown, final String secondKnown) {
		logger.info("Start combining two known versions {}, {} into {}", firstKnown, secondKnown, combinedVersion);
		final File firstZipFile = releaseDataManager.getZipFileForKnownRelease(firstKnown);
		final File secondZipFile = releaseDataManager.getZipFileForKnownRelease(secondKnown);
		releaseDataManager.loadSnomedData(combinedVersion, firstZipFile , secondZipFile);
		logger.info("Complete combining two known versions {}, {} into {}", firstKnown, secondKnown, combinedVersion);
	}*/

	private void runAssertionTests(final ExecutionConfig executionConfig, final Map<String, Object> responseMap, String reportStorage) throws IOException {
		final long timeStart = System.currentTimeMillis();
		final List<AssertionGroup> groups = assertionService.getAssertionGroupsByNames(executionConfig.getGroupNames());
		//execute common resources for assertions before executing group in the future we should run tests concurrently
		final List<Assertion> resourceAssertions = assertionService.getResourceAssertions();
		logger.info("Found total resource assertions need to be run before test: " + resourceAssertions.size());
		reportService.writeProgress("Start executing assertions...", reportStorage);
		 final List<TestRunItem> items = executeAssertions(executionConfig, resourceAssertions, reportStorage);
		final Set<Assertion> assertions = new HashSet<>();
		for (final AssertionGroup group : groups) {
			for (final Assertion assertion : assertionService.getAssertionsForGroup(group)) {
				assertions.add(assertion);
			}
		}
		logger.info("Total assertions to run: " + assertions.size());
		if (batchSize == 0) {
			items.addAll(executeAssertions(executionConfig, assertions, reportStorage));
		} else {
			items.addAll(executeAssertionsConcurrently(executionConfig,assertions, batchSize, reportStorage));
		}
		

		//failed tests
		final List<TestRunItem> failedItems = new ArrayList<>();
		for (final TestRunItem item : items) {
			if (item.getFailureCount() != 0) {
				failedItems.add(item);
			}
		}
		final long timeEnd = System.currentTimeMillis();
		final ValidationReport report = new ValidationReport(TestType.SQL);
		report.setExecutionId(executionConfig.getExecutionId());
		report.setTotalTestsRun(items.size());
		report.setTimeTakenInSeconds((timeEnd - timeStart) / 1000);
		report.setTotalFailures(failedItems.size());
		report.setFailedAssertions(failedItems);
		items.removeAll(failedItems);
		report.setPassedAssertions(items);
		responseMap.put(report.getTestType().toString() + "TestResult", report);
		
	}

	private boolean checkKnownVersion(final String prevIntReleaseVersion, final String previousExtVersion, 
			final String extensionBaseLine, final Map<String, Object> responseMap) {
		logger.debug("Checking known versions...");
		if (previousExtVersion != null) {
			if (extensionBaseLine == null) {
				responseMap.put(FAILURE_MESSAGE, "PreviousExtensionVersion is :" 
						+ prevIntReleaseVersion + " but extension release base line has not been specified.");
				return true;
			}
		}
		if (prevIntReleaseVersion == null && previousExtVersion == null && extensionBaseLine == null) {
			responseMap.put(FAILURE_MESSAGE, "None of the known release version is specified");
			return true;
		}
		boolean isFailed = false;
		if (prevIntReleaseVersion != null && !prevIntReleaseVersion.isEmpty()) {
			if (!isKnownVersion(prevIntReleaseVersion, responseMap)) {
				isFailed = true;
			}
		}
		if (previousExtVersion != null && !previousExtVersion.isEmpty()) {
			if (!isKnownVersion(previousExtVersion, responseMap)) {
				isFailed = true;
			}
		}
		if (extensionBaseLine != null && !extensionBaseLine.isEmpty()) {
			if (!isKnownVersion(extensionBaseLine, responseMap)) {
				isFailed = true;
			}
		}
		return isFailed;
	}

	private void uploadProspectiveVersion(final String prospectiveVersion, final String knownVersion, final File tempFile, 
			final List<String> rf2FilesLoaded) throws ConfigurationException, BusinessServiceException {
		
		if (knownVersion != null && !knownVersion.trim().isEmpty()) {
			logger.info(String.format("Baseline verison: [%1s] will be combined with prospective release file: [%2s]", knownVersion, tempFile.getName()));
			//load them together here as opposed to clone the existing DB so that to make sure it is clean.
			String versionDate = knownVersion;
			if (knownVersion.length() > 8) {
				versionDate = knownVersion.substring(knownVersion.length() - 8);
			}
			final File preLoadedZipFile = releaseDataManager.getZipFileForKnownRelease(versionDate);
			if (preLoadedZipFile != null) {
				logger.info("Start loading release version {} with release file {} and baseline {}", 
						prospectiveVersion, tempFile.getName(), preLoadedZipFile.getName());
				releaseDataManager.loadSnomedData(prospectiveVersion,rf2FilesLoaded, tempFile, preLoadedZipFile);
			} else {
				throw new ConfigurationException("Can't find the cached release zip file for known version: " + versionDate);
			}
		} else {
			logger.info("Start loading release version {} with release file {}", prospectiveVersion, tempFile.getName());
			releaseDataManager.loadSnomedData(prospectiveVersion,rf2FilesLoaded, tempFile);
		}
		logger.info("Completed loading release version {}", prospectiveVersion);
	}

	private boolean isKnownVersion(final String vertionToCheck, final Map<String, Object> responseMap) {
		if (!releaseDataManager.isKnownRelease(vertionToCheck)) {
			// the previous published release must already be present in database, otherwise we throw an error!
			responseMap.put("type", "post");
			final String errorMsg = "Please load published release data in RVF first for version: " + vertionToCheck;
			responseMap.put(FAILURE_MESSAGE, errorMsg);
			logger.info(errorMsg);
			return false;
		}
		return true;
	} 
	


	private List<TestRunItem> executeAssertionsConcurrently(final ExecutionConfig executionConfig, final Collection<Assertion> assertions, int batchSize, String reportStorage) {
		
		final List<Future<Collection<TestRunItem>>> tasks = new ArrayList<>();
		final List<TestRunItem> results = new ArrayList<>();
		int counter = 1;
		List<Assertion> batch = null;
		for (final Assertion assertion: assertions) {
			if (batch == null) {
				batch = new ArrayList<Assertion>();
			}
			batch.add(assertion);
			if (counter % batchSize == 0 || counter == assertions.size()) {
				final List<Assertion> work = batch;
				logger.info(String.format("Started executing assertion [%1s] of [%2s]", counter, assertions.size()));
				final Future<Collection<TestRunItem>> future = executorService.submit(new Callable<Collection<TestRunItem>>() {
					@Override
					public Collection<TestRunItem> call() throws Exception {
						return assertionExecutionService.executeAssertions(work, executionConfig);
					}
				});
				logger.info(String.format("Finished executing assertion [%1s] of [%2s]", counter, assertions.size()));
				//reporting every 10 assertions
				reportService.writeProgress(String.format("[%1s] of [%2s] assertions are started.", counter, assertions.size()), reportStorage);
				tasks.add(future);
				batch = null;
			}
			counter++;
		}
		
		// Wait for all concurrent tasks to finish
		for (final Future<Collection<TestRunItem>> task : tasks) {
			try {
				results.addAll(task.get());
			} catch (ExecutionException | InterruptedException e) {
				logger.error("Thread interrupted while waiting for future result for run item:" + task , e);
			}
		}
		return results;
	}

	private List<TestRunItem> executeAssertions(final ExecutionConfig executionConfig, final Collection<Assertion> assertions, String reportStorage) {
		
		final List<TestRunItem> results = new ArrayList<>();
		int counter = 1;
		for (final Assertion assertion: assertions) {
			logger.info(String.format("Started executing assertion [%1s] of [%2s] with uuid : [%3s]", counter, assertions.size(), assertion.getUuid()));
			results.addAll(assertionExecutionService.executeAssertion(assertion, executionConfig));
			logger.info(String.format("Finished executing assertion [%1s] of [%2s] with uuid : [%3s]", counter, assertions.size(), assertion.getUuid()));
			counter++;
			if (counter % 10 == 0) {
				//reporting every 10 assertions
				reportService.writeProgress(String.format("[%1s] of [%2s] assertions are completed.", counter, assertions.size()), reportStorage);
			}
		}
		reportService.writeProgress(String.format("[%1s] of [%2s] assertions are completed.", counter, assertions.size()), reportStorage);
		return results;
	}
}
