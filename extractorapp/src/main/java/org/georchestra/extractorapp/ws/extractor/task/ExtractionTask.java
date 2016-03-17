/*
 * Copyright (C) 2009-2016 by the geOrchestra PSC
 *
 * This file is part of geOrchestra.
 *
 * geOrchestra is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * geOrchestra is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * geOrchestra.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.georchestra.extractorapp.ws.extractor.task;

import java.io.*;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.georchestra.extractorapp.ws.extractor.ExtractorController;
import org.georchestra.extractorapp.ws.extractor.ExtractorLayerRequest;
import org.georchestra.extractorapp.ws.extractor.FileUtils;
import org.georchestra.extractorapp.ws.extractor.OversizedCoverageRequestException;
import org.georchestra.extractorapp.ws.extractor.RequestConfiguration;
import org.georchestra.extractorapp.ws.extractor.WcsExtractor;
import org.georchestra.extractorapp.ws.extractor.WfsExtractor;
import org.georchestra.extractorapp.ws.extractor.csw.CSWExtractor;
import org.json.JSONException;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.NoSuchAuthorityCodeException;
import org.opengis.referencing.operation.TransformException;


/**
 * Thread responsible for downloading all the data for a single request and
 * emailing the link for obtaining the data.
 *
 * @author jeichar
 */
public class ExtractionTask implements Runnable, Comparable<ExtractionTask> {
	private static final Log LOG = LogFactory.getLog(ExtractionTask.class
			.getPackage().getName());

	private static final int EXTRACTION_ATTEMPTS = 3;
	public final ExecutionMetadata executionMetadata;

	private RequestConfiguration requestConfig;

	public ExtractionTask(RequestConfiguration requestConfig)
			throws NoSuchAuthorityCodeException, MalformedURLException, JSONException, FactoryException {
		this.requestConfig = requestConfig;
		this.executionMetadata = new ExecutionMetadata(
				this.requestConfig.requestUuid,
				this.requestConfig.username,
				new Date(),
				this.requestConfig.strRequest);
	}
	public ExtractionTask(ExtractionTask toCopy) {

		this.requestConfig = toCopy.requestConfig;
		this.executionMetadata = toCopy.executionMetadata;
	}


	@Override
	public void run() {
		executionMetadata.setRunning();
		requestConfig.setThreadLocal();

		final File tmpDir = FileUtils.createTempDirectory();
		final File tmpExtractionBundle = mkDirTmpExtractionBundle(tmpDir, requestConfig.extractionFolderPrefix+requestConfig.requestUuid .toString());

		try {
			long start = System.currentTimeMillis();
			LOG.info("Starting extraction into directory: "
					+ tmpExtractionBundle);

			final File failureFile = new File(tmpExtractionBundle,
					"failures.txt");
			final List<String> successes = new ArrayList<String>();
			final List<String> failures = new ArrayList<String>();
			final List<String> oversized = new ArrayList<String>();
			for (ExtractorLayerRequest request : requestConfig.requests) {

				int tries = 0;
				while (tries < EXTRACTION_ATTEMPTS) {

					tries++;
					String name = String.format("%s__%s",
							request._url.getHost(), request._layerName);
					File layerTmpDir = mkDirTmpExtractionBundle(tmpDir, name);
					LOG.info("Attempt " + tries + " for extracting layer: "
							+ request._url + " -- " + request._layerName);

					try {
						// extracts the layer in the temporal directory
						File newDir;
						switch (request._owsType) {
						case WCS:
							newDir = extractWcsLayer(request, layerTmpDir);
							break;
						case WFS:
							newDir = extractWfsLayer(request, layerTmpDir);
							break;
						default:
							throw new IllegalArgumentException(request._owsType
									+ " not supported");
						}
						// extracts the metadata into the temporal directory
						if(request._isoMetadataURL != null && !"".equals(request._isoMetadataURL) ){
							extractMetadata(request, newDir);
						}

						for (File from : layerTmpDir.listFiles()) {
							File to = new File(tmpExtractionBundle,
									from.getName());
							FileUtils.moveFile(from, to);
						}
						FileUtils.delete(layerTmpDir);
						LOG.info("Finished extracting layer: " + request._url
								+ " -- " + request._layerName);
						tries = EXTRACTION_ATTEMPTS + 1;
						successes.add(name);
					} catch (OversizedCoverageRequestException e) {
						tries = EXTRACTION_ATTEMPTS + 1; // don't re-try
						oversized.add(name);
						handleExtractionException(request, e, failureFile);
					} catch (SecurityException e) {
						tries = EXTRACTION_ATTEMPTS + 1; // don't re-try

						try {
							FileUtils.delete(layerTmpDir);
						} catch (Throwable t) { /* ignore */
						}

						failures.add(name);
						handleExtractionException(request, e, failureFile);
					} catch (Throwable e) {
						try {
							FileUtils.delete(layerTmpDir);
						} catch (Throwable t) { /* ignore */
						}

						if (tries >= EXTRACTION_ATTEMPTS) {
							failures.add(name);
							handleExtractionException(request, e, failureFile);
						}
					}
				}
			}

			File archive = archiveExtraction(tmpExtractionBundle);
			long fileSize = archive.length();
			long end = System.currentTimeMillis();

			String msg = String
					.format("Finished extraction into directory: %s achive is: %s (size : %s bytes) \nExtraction took %s",
							tmpExtractionBundle, archive, fileSize, time(start, end));
			LOG.info(msg);

			if (!requestConfig.testing) {
				try {
					requestConfig.email.sendDone(successes, failures, oversized, fileSize);
				} catch (Throwable e) {
					handleException(e);
				}
			} else if (requestConfig.testing && !failures.isEmpty()) {
				throw new RuntimeException(Arrays.toString(failures.toArray()));
			}
		} finally {
			executionMetadata.setCompleted();
			FileUtils.delete(tmpExtractionBundle);
			FileUtils.delete(tmpDir);

		}
	}

	private String time(long start, long end) {
		long seconds = (end - start) / 1000;
		if (seconds > 60) {
			long minutes = seconds / 60;
			seconds = seconds - (minutes * 60);
			if (minutes > 24) {
				long hours = minutes / 24;
				minutes = minutes - (hours * 24);
				return (hours + " hour " + minutes + " min");
			}
			return minutes + " min " + seconds + " sec";
		}

		return seconds + " seconds";
	}

	// ----------------- support methods ----------------- //
	/**
	 * Protected to allow unit test to override
	 * @throws AssertionError
	 * @throws IOException
	 */
	protected File mkDirTmpExtractionBundle(File tmpDir, String name) {

		File tmpExtractionBundle = new File(tmpDir, FileUtils.toSafeFileName(name));
		if (!tmpExtractionBundle.mkdirs() && !tmpExtractionBundle.exists()) {
			throw new RuntimeException("Unable to make directory: "+tmpExtractionBundle);
		}
		return tmpExtractionBundle;
	}

	/**
	 * Protected to allow unit test to override
	 *
	 * @return
	 */
	protected File archiveExtraction(File tmpExtractionBundle) {
		String filename = requestConfig.requestUuid.toString()
				+ ExtractorController.EXTRACTION_ZIP_EXT;
		File storageFile = FileUtils.storageFile(filename);
		if (!storageFile.getParentFile().exists()) {
			storageFile.getParentFile().mkdirs();
		}
		try {
			FileUtils.archiveToZip(tmpExtractionBundle, storageFile);
		} catch (IOException e1) {
			handleException(e1);
		}
		return storageFile;
	}

	private void handleExtractionException(ExtractorLayerRequest request,
			Throwable e, File failureFile) {
		if (!failureFile.getParentFile().exists()) {
			throw new AssertionError(
					"The temporary extraction bundle directory: "
							+ failureFile.getParentFile() + " does not exist");
		}

		String msg = "Exception occurred while extracting data";
		LOG.error(msg, e);

		StringWriter stackTrace = new StringWriter();
		e.printStackTrace(new PrintWriter(stackTrace));
		openFailuresFile(failureFile);
		String message = String
				.format("\nError accessing layer: %s \n"
						+ "\n"
						+ " * Service: %s \n"
						+ " * Layer: %s \n"
						+ " * Exception: \n"
						+ " %s \n", request._layerName,
						request._url, request._layerName, e.getLocalizedMessage().substring(0, Math.min(200,
						e.getLocalizedMessage().length())),
						stackTrace.toString());
		writeToFile(failureFile, message, true);
	}

	private void openFailuresFile(File failureFile) {
		if (!failureFile.exists()) {
			String msg = "There were errors during the extraction process\n"
					+ "All services have been polled "
					+ EXTRACTION_ATTEMPTS
					+ " times.\n";
			writeToFile(failureFile, msg, false);
		}
	}

	private void writeToFile(File failureFile, String message, boolean append) {
		FileOutputStream writer = null;
		try {
			writer = new FileOutputStream(failureFile, append);
			writer.write(message.getBytes("UTF-8"));
		} catch (IOException e1) {
			handleException(e1);
		} finally {
			if (writer != null) {
				try {
					writer.close();
				} catch (IOException e2) {
					handleException(e2);
				}
			}
		}
	}

	private void handleException(Throwable e1) {
		// TODO handle failure. What am I supposed to do about it?
		e1.printStackTrace();
	}


	/**
	 * Creates a directory which contains the extracted layers
	 *
	 * @param request
	 * @param requestBaseDir
	 * @return the directory that contain the layers
	 *
	 * @throws IOException
	 * @throws TransformException
	 * @throws FactoryException
	 */
	private File  extractWcsLayer(ExtractorLayerRequest request, File requestBaseDir)
			throws IOException, TransformException, FactoryException {

		WcsExtractor extractor = new WcsExtractor(requestBaseDir, requestConfig);

		extractor.checkPermission(request, requestConfig.secureHost, requestConfig.username, requestConfig.roles);

		return extractor.extract(request);
	}

	/**
	 * Creates a directory which contains the extracted layers
	 * @param request
	 * @param requestBaseDir
	 *
	 * @return the directory that contain the layers
	 *
	 * @throws IOException
	 * @throws TransformException
	 * @throws FactoryException
	 */
	private File extractWfsLayer(ExtractorLayerRequest request, File requestBaseDir)
			throws IOException, TransformException, FactoryException {

		WfsExtractor extractor = new WfsExtractor(requestBaseDir,
				requestConfig.adminCredentials.getUserName(),
				requestConfig.adminCredentials.getPassword(),
				requestConfig.secureHost,
				requestConfig.userAgent);

		extractor.checkPermission(request, requestConfig.secureHost, requestConfig.username, requestConfig.roles);

		return extractor.extract(request);
	}

	/**
	 * Extracts the layer's metadata and save it in the layer directory.
	 *
	 * @param request
	 * @param layerDirectory
	 * @throws MalformedURLException
	 * @throws IOException
	 */
	private void extractMetadata(final ExtractorLayerRequest request, final File layerDirectory) throws IOException {

		final String adminUserName = requestConfig.adminCredentials.getUserName();
		final String adminPassword = requestConfig.adminCredentials.getPassword();

		String cswHost = request._isoMetadataURL.getHost();

		CSWExtractor extractor = new CSWExtractor(layerDirectory, adminUserName, adminPassword, cswHost, requestConfig.userAgent);

		extractor.checkPermission(request, requestConfig.username, requestConfig.roles);

		extractor.extract(request._isoMetadataURL);
	}


	@Override
	public int compareTo(ExtractionTask other) {

		return other.executionMetadata.getPriority().compareTo(
				this.executionMetadata.getPriority());
// Replaced because this code order from low to high
//		return executionMetadata.getPriority().compareTo(
//				other.executionMetadata.getPriority());
	}

	public boolean equalId(String uuid) {
		return requestConfig.requestUuid.toString().equals(uuid);
	}
}
