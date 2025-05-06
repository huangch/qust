/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2023 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.qust;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.imgscalr.Scalr;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import hdf.hdf5lib.HDF5Constants;
import hdf.hdf5lib.H5;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.common.GeneralTools;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.plugins.AbstractInteractivePlugin;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Experimental plugin to help with the quantification of stardist cell nuclei structures.
 * 
 * @author Chao Hui Huang
 *
 */
public class RegionSegmentationImageAcquisition extends AbstractInteractivePlugin<BufferedImage> {
	protected static QuSTSetup qustSetup = QuSTSetup.getInstance();
	
	private static Logger logger = LoggerFactory.getLogger(RegionSegmentationImageAcquisition.class);
	private static String lastResults = null;
	
	private static StringProperty regSegImgAcqDistProp = PathPrefs.createPersistentPreference("regSegImgAcqDist", "");
//	private static BooleanProperty regSegImgAcqDontRescalingProp = PathPrefs.createPersistentPreference("regSegImgAcqDontRescaling", true);
//	private static BooleanProperty regSegImgAcqNormalizationProp = PathPrefs.createPersistentPreference("regSegImgAcqNormalization", true);
	private static DoubleProperty regSegImgAcqMppProp = PathPrefs.createPersistentPreference("regSegImgAcqMpp",0.21233);
	private static IntegerProperty regSegImgAcqSamplingSizeProp = PathPrefs.createPersistentPreference("regSegImgAcqSamplingSize", 224);
	private static IntegerProperty regSegImgAcqSamplingStrideProp = PathPrefs.createPersistentPreference("regSegImgAcqSamplingStride", 112);
	private static BooleanProperty regSegImgAcqAllSamplesProp = PathPrefs.createPersistentPreference("regSegImgAcqAllSamplesProp", true);
	private static IntegerProperty regSegImgAcqSamplingNumProp = PathPrefs.createPersistentPreference("regSegImgAcqSamplingNum", 0);
	
	private static ParameterList params;
	
	/**
	 * Default constructor.
	 */
	public RegionSegmentationImageAcquisition() {
		params = new ParameterList()
			.addStringParameter("dist", "Distination", regSegImgAcqDistProp.get(), "Distination Folder or File")
			.addChoiceParameter("format", "Format ", "Folder", Arrays.asList("Folder", "WebDataset", "HDF5"), "Choose which format to use")
        	.addEmptyParameter("").addEmptyParameter("Reampling using...")
			.addBooleanParameter("normalization", "Normalization (default: yes)", true, "Normalization (default: no)")
			.addBooleanParameter("dontResampling", "Do not rescaling image (default: yes)", true, "Do not rescaling image (default: yes)")
			.addEmptyParameter("...or...")
			.addDoubleParameter("mpp", "pixel size", regSegImgAcqMppProp.get(), GeneralTools.micrometerSymbol(), "Pixel Size")
			.addEmptyParameter("")
			.addIntParameter("samplingSize", "Sampling Size", regSegImgAcqSamplingSizeProp.get(), "pixel(s)", "Sampling Size")
			.addIntParameter("samplingStride", "Sampling Stride", regSegImgAcqSamplingStrideProp.get(), "pixel(s)", "Sampling Stride")
			.addBooleanParameter("allSamples", "Include all samples (default: yes)", regSegImgAcqAllSamplesProp.get(), "Include all samples? (default: yes)")
			.addEmptyParameter("...or...")
			.addIntParameter("samplingNum", "Maximal Sampling Number", regSegImgAcqSamplingNumProp.get(), "objects(s)", "Maximal Sampling Number")
			;
	}

	
	@Override
	public boolean runPlugin(TaskRunner taskRunner, ImageData<BufferedImage> imageData, String arg) {
		boolean success = super.runPlugin(taskRunner, imageData, arg);
		imageData.getHierarchy().fireHierarchyChangedEvent(this);
		return success;
	}
	
	
	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		ParameterList params = getParameterList(imageData);
		
		tasks.add(new RegionSegmentationRunnable(imageData, parentObject, params));
	}
	
	
	static class RegionSegmentationRunnable implements Runnable {
		private ImageData<BufferedImage> imageData;
		private ParameterList params;
		private PathObject parentObject;
		private MacenkoStainingNormalizer normalizer = new MacenkoStainingNormalizer();

		public RegionSegmentationRunnable(ImageData<BufferedImage> imageData, PathObject parentObject, ParameterList params) {
			this.imageData = imageData;
			this.parentObject = parentObject;
			this.params = params;
		}

		
		@Override
		public void run() {
			if (!(parentObject instanceof PathAnnotationObject) || !parentObject.hasChildObjects()) {
				return;
			}
			
			regSegImgAcqMppProp.set(params.getDoubleParameterValue("mpp"));
			regSegImgAcqSamplingSizeProp.set(params.getIntParameterValue("samplingSize"));
			regSegImgAcqSamplingStrideProp.set(params.getIntParameterValue("samplingStride"));
			regSegImgAcqAllSamplesProp.set(params.getBooleanParameterValue("allSamples"));
			regSegImgAcqSamplingNumProp.set(params.getIntParameterValue("samplingNum"));
			
			ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
			String serverPath = server.getPath();
			
			double imageMpp = server.getPixelCalibration().getAveragedPixelSizeMicrons();
			double scalingFactor = params.getBooleanParameterValue("dontResampling") ? 1.0: regSegImgAcqMppProp.get() / imageMpp;
			int featureSize = (int)(0.5 + scalingFactor * regSegImgAcqSamplingSizeProp.get());
			int stride = (int)(0.5 + scalingFactor * regSegImgAcqSamplingStrideProp.get());
			
			int segmentationWidth = 1+(int)((double)(server.getWidth()-featureSize)/(double)stride);
			int segmentationHeight = 1+(int)((double)(server.getHeight()-featureSize)/(double)stride);
			
			List<RegionRequest> allRegionList = Collections.synchronizedList(new ArrayList<RegionRequest>());
			List<RegionRequest> availRegionList = Collections.synchronizedList(new ArrayList<RegionRequest>());

			AtomicBoolean success = new AtomicBoolean(true);
			
			IntStream.range(0, segmentationHeight).parallel().forEach(y -> {
				if (!success.get()) return;
				
				IntStream.range(0, segmentationWidth).parallel().forEach(x -> {
					if (!success.get()) return;
					
					int alignedY = stride*y;
					int alignedX = stride*x;
					try {
						synchronized(allRegionList) {
							allRegionList.add(RegionRequest.createInstance(serverPath, 1.0, alignedX, alignedY, featureSize, featureSize));
						}
					} catch (Exception e) {
			        	e.printStackTrace();
			        	success.set(false);
						return;
					}
				});
			});

			if (!success.get()) {
				logger.error("Region segmentation data preparation failed!");
				return;
			}
			
			ROI annotObjRoi = parentObject.getROI();
			Geometry annotObjRoiGeom = annotObjRoi.getGeometry();
			
			allRegionList.parallelStream().forEach(r -> {
				ROI regionRoi = ROIs.createRectangleROI(r);
				Geometry intersect = annotObjRoiGeom.intersection(regionRoi.getGeometry());
				
				if (!intersect.isEmpty()) 
					availRegionList.add(r);
			});
			
			Collections.shuffle(availRegionList);

			QuSTSetup QuSTOptions = QuSTSetup.getInstance();
			int normalizationSampleSize = QuSTOptions.getNormalizationSampleSize();						
			
			if (availRegionList.size() < normalizationSampleSize) {
				logger.error("Object number is smaller than the required number for normalization");
				return;
			}
			List<RegionRequest> normalizationSamplingRegionList = Collections
					.synchronizedList(availRegionList.subList(0, normalizationSampleSize));						
			
			List<BufferedImage> normalizationSamplingImageList = Collections.synchronizedList(new ArrayList<>());
			
			normalizationSamplingRegionList.stream().parallel().forEach(r -> {
				if (!success.get()) return;
				
				BufferedImage imgContent = null;
				
				try {	
					imgContent = (BufferedImage) server.readRegion(r);
				}
		        catch (Exception e) {
		        	e.printStackTrace();
		        	success.set(false);
		        	return;
				}
				
				BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
				imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);
				normalizationSamplingImageList.add(imgBuf);
			});
			
			if (!success.get()) {
				logger.error("Region segmentation data preparation failed!");
				return;
			}
			
			BufferedImage normalizationSamplingImage = normalizer.concatBufferedImages(normalizationSamplingImageList);
			final double[][] est_W = normalizer.reorderStainsByCosineSimilarity(normalizer.getStainMatrix(normalizationSamplingImage, normalizer.OD_threshold, 1), normalizer.targetStainReferenceMatrix);
			
			int samplingNum = params.getIntParameterValue("samplingNum") == 0
					|| params.getIntParameterValue("samplingNum") > availRegionList.size() 
					|| params.getBooleanParameterValue("allSamples") 
					? availRegionList.size()
							: params.getIntParameterValue("samplingNum");
					
			List<RegionRequest> samplingRegionList = Collections
					.synchronizedList(availRegionList.subList(0, samplingNum));
			
			String imgFmt = qustSetup.getImageFileFormat().strip();
        	String fileExt = imgFmt.charAt(0) == '.' ? imgFmt.substring(1) : imgFmt;					
			
			String saveLocation = Paths.get(regSegImgAcqDistProp.get()).toString();
			String saveType = (String)params.getChoiceParameterValue("format");

			if(saveType == "Folder") {
				new File(saveLocation).mkdirs();
				
				samplingRegionList.subList(0, samplingNum).parallelStream().forEach(r -> {
					if (!success.get()) return;
					
					try {	
						BufferedImage imgContent = (BufferedImage) server.readRegion(r);
						BufferedImage imgBuffer = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
						imgBuffer.getGraphics().drawImage(imgContent, 0, 0, null);
						
						BufferedImage outputImgBuf = params.getBooleanParameterValue("normalization")? 
								normalizer.normalizeToReferenceImage(imgBuffer, est_W, normalizer.targetStainReferenceMatrix): imgBuffer;
						
						BufferedImage imgSampled = params.getBooleanParameterValue("dontResampling") ? outputImgBuf
								: Scalr.resize(outputImgBuf, params.getIntParameterValue("samplingSize"));
						
						String filename = parentObject.getID().toString()+"."+Integer.toString(r.getX())+"-"+Integer.toString(r.getY());
						Path imageFilePath = Paths.get(saveLocation, filename + "." + fileExt);
						File imageFile = new File(imageFilePath.toString());
						ImageIO.write(imgSampled, fileExt, imageFile);
					}
			        catch (Exception e) {
			        	e.printStackTrace();
			        	success.set(false);
			        	return;
					}
				});
			} else if (saveType == "WebDataset") {
				Iterator<RegionRequest> samplingRegionRequestIterator = samplingRegionList.iterator();
				
				class ImageLabelPair {
				   String idx;
				   byte[] imgBytes;
				   byte[] labelBytes;
				   ImageLabelPair(byte[] imgBytes, byte[] labelBytes) {
				       this.imgBytes = imgBytes;
				       this.labelBytes = labelBytes;
				   }
				}
					
				int batchSize = 1000;
				int batchNum = 0;

				// Write this batch to tar
        		try (TarArchiveOutputStream tarOut = new TarArchiveOutputStream(
        				new BufferedOutputStream(new FileOutputStream(saveLocation)))) {
        		
		        	while (samplingRegionRequestIterator.hasNext()) {
		        		List<BufferedImage> imageBatch = new ArrayList<>(batchSize);
		        		List<String> labelBatch = new ArrayList<>(batchSize);
		        		// Load next batch (avoiding OOM)
						
		        		for (int i = 0; i < batchSize && samplingRegionRequestIterator.hasNext(); i++) {
		        			RegionRequest r = samplingRegionRequestIterator.next();
		        			BufferedImage imgContent = (BufferedImage) server.readRegion(r);
							BufferedImage imgBuffer = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
							imgBuffer.getGraphics().drawImage(imgContent, 0, 0, null);
							
							BufferedImage outputImgBuf = params.getBooleanParameterValue("normalization")? 
									normalizer.normalizeToReferenceImage(imgBuffer, est_W, normalizer.targetStainReferenceMatrix): imgBuffer;
							
							BufferedImage imgSampled = params.getBooleanParameterValue("dontResampling") ? outputImgBuf
									: Scalr.resize(outputImgBuf, params.getIntParameterValue("samplingSize"));
							
							imageBatch.add(imgSampled);
		        			labelBatch.add(parentObject.getID().toString());
		        		}
		        		if (imageBatch.isEmpty()) break;
			           
		        		// Parallel encoding
		        		List<ImageLabelPair> encodedBatch = IntStream.range(0, imageBatch.size()).parallel().mapToObj(i -> {
		        			try {
		        				// Encode image
			                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
			                    ImageIO.write(imageBatch.get(i), fileExt, baos);
			                    byte[] imgBytes = baos.toByteArray();
			                    baos.close();
			                    // Encode label
			                    byte[] labelBytes = labelBatch.get(i) != null ?
			                    		labelBatch.get(i).getBytes(StandardCharsets.UTF_8) : null;
			                    return new ImageLabelPair(imgBytes, labelBytes);
			                } catch (Exception e) {
			                	throw new RuntimeException(e);
			                }
		        		})
		        		.collect(Collectors.toList());
					
	        			for (ImageLabelPair item : encodedBatch) {
	        				// Write image
	        				TarArchiveEntry imgEntry = new TarArchiveEntry(item.idx + '.' + fileExt);
	        				imgEntry.setSize(item.imgBytes.length);
	        				tarOut.putArchiveEntry(imgEntry);
	        				tarOut.write(item.imgBytes);
	        				tarOut.closeArchiveEntry();
	        				// Write label
	        				if (item.labelBytes != null) {
	        					TarArchiveEntry labelEntry = new TarArchiveEntry(item.idx + ".cls");
	        					labelEntry.setSize(item.labelBytes.length);
	        					tarOut.putArchiveEntry(labelEntry);
	        					tarOut.write(item.labelBytes);
	        					tarOut.closeArchiveEntry();
	        				}
	        			}
	        			
		        		logger.info("Wrote batch %d to %s%n", batchNum, saveLocation);
		        		batchNum++;
		        		// Release memory for next batch
		        		imageBatch.clear();
		        		labelBatch.clear();
		        		encodedBatch.clear();
		        		System.gc(); // optional, may help with memory	        			
	        		}
		        } catch (Exception e) {
					e.printStackTrace();
					logger.error(e.toString());
					return;
				}
				
				if (!success.get()) {
					logger.error("Something went wrong");
					return;
				}
				
			} else if (saveType == "HDF5") {
				String osName = System.getProperty("os.name").toLowerCase();
				
				String libResourcePath = "/native/libhdf5_java.so";
				
				if (osName.contains("nix") || osName.contains("nux")) {
					libResourcePath = "/native/linux/libhdf5_java.so";
				} else {
					logger.error("Unsupported OS: "+ osName);
					return;
				}
				
				InputStream in = RegionSegmentationImageAcquisition.class.getResourceAsStream(libResourcePath);
				if (in == null) {
					logger.error("Native library not found: "+libResourcePath);
					return;
				}
				File temp = null;
				try {
					temp = File.createTempFile("libhdf5_java", ".so");
				} catch (IOException e) {
					e.printStackTrace();
					logger.error(e.toString());
					return;
				}
				
				temp.deleteOnExit();
				
				try (OutputStream out = new FileOutputStream(temp)) {
					byte[] buf = new byte[4096];
					int len;
					while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
				} catch (Exception e) {
					e.printStackTrace();
					logger.error(e.toString());
					return;
				}
				
				System.load(temp.getAbsolutePath());
				
				int StrLenCnt = 0;
				
				for(RegionRequest r: samplingRegionList) {
					String batchIds = parentObject.getID().toString()+"."+Integer.toString(r.getX())+"-"+Integer.toString(r.getY());
					String batchLabels = parentObject.getID().toString();
					byte[] batchIdUtf = batchIds.getBytes(StandardCharsets.UTF_8);
					byte[] batchLabelUtf = batchLabels.getBytes(StandardCharsets.UTF_8);
					 
					if (StrLenCnt < batchIdUtf.length) StrLenCnt = batchIdUtf.length;
					if (StrLenCnt < batchLabelUtf.length) StrLenCnt = batchLabelUtf.length;
				}
							
				final int maxStrLen = StrLenCnt;			
				int batchSize = 1000;
				
				// Create HDF5 file
				long file_id = H5.H5Fcreate(saveLocation.toString(), HDF5Constants.H5F_ACC_TRUNC,
						HDF5Constants.H5P_DEFAULT, HDF5Constants.H5P_DEFAULT);
				// Create image dataset
				long[] chunk_dims = {batchSize, featureSize, featureSize, 3};
				long[] max_dims = {HDF5Constants.H5S_UNLIMITED, featureSize, featureSize, 3};
				long[] init_dims = {0, featureSize, featureSize, 3};
				long dataspace_id = H5.H5Screate_simple(4, init_dims, max_dims);
				long plist_id = H5.H5Pcreate(HDF5Constants.H5P_DATASET_CREATE);
				H5.H5Pset_chunk(plist_id, 4, chunk_dims);
				H5.H5Pset_deflate(plist_id, 6);
				long image_dtype = HDF5Constants.H5T_NATIVE_UINT8;
				long image_dset_id = H5.H5Dcreate(file_id, "images", image_dtype,
						dataspace_id, HDF5Constants.H5P_DEFAULT, plist_id, HDF5Constants.H5P_DEFAULT);
				// String type (variable length UTF-8)
				long str_type = H5.H5Tcopy(HDF5Constants.H5T_C_S1);
				H5.H5Tset_size(str_type, maxStrLen);
				H5.H5Tset_strpad(str_type, HDF5Constants.H5T_STR_NULLTERM);
				H5.H5Tset_cset(str_type, HDF5Constants.H5T_CSET_UTF8);
				long[] str_init = {0};
				long[] str_max = {HDF5Constants.H5S_UNLIMITED};
				long str_space = H5.H5Screate_simple(1, str_init, str_max);
				long str_plist = H5.H5Pcreate(HDF5Constants.H5P_DATASET_CREATE);
				H5.H5Pset_chunk(str_plist, 1, new long[]{batchSize});
				long id_dset = H5.H5Dcreate(file_id, "ids", str_type,
						str_space, HDF5Constants.H5P_DEFAULT, str_plist, HDF5Constants.H5P_DEFAULT);
				long label_dset = H5.H5Dcreate(file_id, "labels", str_type,
						str_space, HDF5Constants.H5P_DEFAULT, str_plist, HDF5Constants.H5P_DEFAULT);
				// Batch processing
				int offset = 0;
				while (offset < samplingRegionList.size()) {
					int end = Math.min(offset + batchSize, samplingRegionList.size());
					int B = end - offset;
					byte[][][][] batchImageBuf = new byte[B][featureSize][featureSize][3];
					byte[][] batchIdBuf = new byte[B][maxStrLen];
					byte[][] batchLabelBuf = new byte[B][maxStrLen];
					final int final_offset = offset;
					IntStream.range(0, B).parallel().forEach(i -> {
						if (!success.get()) return;
						
						RegionRequest r = samplingRegionList.get(final_offset + i);
						BufferedImage imgContent = null;
						
						try {
							imgContent = (BufferedImage) server.readRegion(r);
						} catch (IOException e) {
							e.printStackTrace();
							success.set(false);
							return;
						}
						
						BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
						imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);
					
						BufferedImage outputImgBuf = params.getBooleanParameterValue("normalization")? 
							   	normalizer.normalizeToReferenceImage(imgBuf, est_W, normalizer.targetStainReferenceMatrix): imgBuf;
					
					   	BufferedImage imgSampled = params.getBooleanParameterValue("dontResampling") ? 
					   			outputImgBuf: Scalr.resize(outputImgBuf, params.getIntParameterValue("samplingSize"));
						
						for (int y = 0; y < featureSize; y++) {
							for (int x = 0; x < featureSize; x++) {
								int rgb = imgSampled.getRGB(x, y);
								batchImageBuf[i][y][x][0] = (byte) ((rgb >> 16) & 0xFF);
								batchImageBuf[i][y][x][1] = (byte) ((rgb >> 8) & 0xFF);
								batchImageBuf[i][y][x][2] = (byte) (rgb & 0xFF);
							}
						}
						
						String batchIdStr = parentObject.getID().toString();
						byte[] batchIdUtfBytes = batchIdStr.getBytes(StandardCharsets.UTF_8);
						int batchIdLen = Math.min(batchIdUtfBytes.length, maxStrLen - 1);
						System.arraycopy(batchIdUtfBytes, 0, batchIdBuf[i], 0, batchIdLen);
						batchIdBuf[i][batchIdLen] = 0;
						
						String batchLabelStr = parentObject.getID().toString()+"."+Integer.toString(r.getX())+"-"+Integer.toString(r.getY());
						byte[] batchLabelUtfBytes = batchLabelStr.getBytes(StandardCharsets.UTF_8);
						int batchLabelLen = Math.min(batchLabelUtfBytes.length, maxStrLen - 1);
						System.arraycopy(batchLabelUtfBytes, 0, batchLabelBuf[i], 0, batchLabelLen);
						batchLabelBuf[i][batchLabelLen] = 0;
					});
					
					if (!success.get()) {
						logger.error("Region segmentation data preparation failed!");
						return;
					}
					
					long newTotal = offset + B;
					// Extend datasets
					H5.H5Dset_extent(image_dset_id, new long[]{newTotal, featureSize, featureSize, 3});
					H5.H5Dset_extent(id_dset, new long[]{newTotal});
					H5.H5Dset_extent(label_dset, new long[]{newTotal});
					// Write image batch
					long img_space = H5.H5Dget_space(image_dset_id);
					H5.H5Sselect_hyperslab(img_space, HDF5Constants.H5S_SELECT_SET,
							new long[]{offset, 0, 0, 0}, null, new long[]{B, featureSize, featureSize, 3}, null);
					long img_mem = H5.H5Screate_simple(4, new long[]{B, featureSize, featureSize, 3}, null);
					H5.H5Dwrite(image_dset_id, image_dtype, img_mem, img_space,
							HDF5Constants.H5P_DEFAULT, batchImageBuf);
					// Write id batch
					long id_space = H5.H5Dget_space(id_dset);
					H5.H5Sselect_hyperslab(id_space, HDF5Constants.H5S_SELECT_SET,
							new long[]{offset}, null, new long[]{B}, null);
					long id_mem = H5.H5Screate_simple(1, new long[]{B}, null);
					H5.H5Dwrite(id_dset, str_type, id_mem, id_space, HDF5Constants.H5P_DEFAULT, batchIdBuf);
					// Write label batch
					long label_space = H5.H5Dget_space(label_dset);
					H5.H5Sselect_hyperslab(label_space, HDF5Constants.H5S_SELECT_SET,
							new long[]{offset}, null, new long[]{B}, null);
					long label_mem = H5.H5Screate_simple(1, new long[]{B}, null);
					H5.H5Dwrite(label_dset, str_type, label_mem, label_space, HDF5Constants.H5P_DEFAULT, batchLabelBuf);
					offset += B;
				}
				// Cleanup
				H5.H5Dclose(image_dset_id);
				H5.H5Dclose(id_dset);
				H5.H5Dclose(label_dset);
				H5.H5Fclose(file_id);	
			} 
		}
		
		@Override
		public String toString() {
			return "Region segmentation to dataset";
		}
	}

	@Override
	protected void preprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {

		String fileName = params.getStringParameterValue("dist");
		String format = (String)params.getChoiceParameterValue("format");
		File regSegImgAcqDistFp;
		
		if(format == "WebDataset") {
			if (fileName.isBlank()) {
				QuPathGUI qupath = QuPathGUI.getInstance();
				
				// Get default name & output directory
				Project<BufferedImage> project = qupath.getProject();
				String defaultName = imageData.getServer().getMetadata().getName();
				
				if (project != null) {
					ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
					if (entry != null)
						defaultName = entry.getImageName();
				}
				
				defaultName = GeneralTools.stripExtension(defaultName);
				File defaultDirectory = project == null || project.getPath() == null ? null : project.getPath().toFile();
				while (defaultDirectory != null && !defaultDirectory.isDirectory())
					defaultDirectory = defaultDirectory.getParentFile();
				File defaultFile = new File(defaultDirectory, defaultName);
				
				regSegImgAcqDistFp = FileChoosers.promptToSaveFile("Export to file", defaultFile,
						FileChoosers.createExtensionFilter("WebDataset file", ".tar"));
				
				if (regSegImgAcqDistFp != null) {
					regSegImgAcqDistProp.set(regSegImgAcqDistFp.toString());
				} else {
					Dialogs.showErrorMessage("Warning", "No output file is selected!");
					lastResults = "No output file is selected!";
					logger.warn(lastResults);
				}
			}
			else {
				regSegImgAcqDistProp.set(fileName);
			}
		} else if(format == "HDF5") {
			if (fileName.isBlank()) {
				QuPathGUI qupath = QuPathGUI.getInstance();
				
				// Get default name & output directory
				Project<BufferedImage> project = qupath.getProject();
				String defaultName = imageData.getServer().getMetadata().getName();
				
				if (project != null) {
					ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
					if (entry != null)
						defaultName = entry.getImageName();
				}
				
				defaultName = GeneralTools.stripExtension(defaultName);
				File defaultDirectory = project == null || project.getPath() == null ? null : project.getPath().toFile();
				while (defaultDirectory != null && !defaultDirectory.isDirectory())
					defaultDirectory = defaultDirectory.getParentFile();
				File defaultFile = new File(defaultDirectory, defaultName);
				
				regSegImgAcqDistFp = FileChoosers.promptToSaveFile("Export to file", defaultFile,
						FileChoosers.createExtensionFilter("HDF5 file", ".h5"));
				
				if (regSegImgAcqDistFp != null) {
					regSegImgAcqDistProp.set(regSegImgAcqDistFp.toString());
				} else {
					Dialogs.showErrorMessage("Warning", "No output file is selected!");
					lastResults = "No output file is selected!";
					logger.warn(lastResults);
				}
			}
			else {
				regSegImgAcqDistProp.set(fileName);
			}
		} else if(format == "Folder") {
			if (fileName.isBlank()) {
				regSegImgAcqDistFp = FileChoosers.promptForDirectory("Output directory", new File(regSegImgAcqDistProp.get()));
	
				if (regSegImgAcqDistFp != null) {
					regSegImgAcqDistProp.set(regSegImgAcqDistFp.toString());
				} else {
					Dialogs.showErrorMessage("Warning", "No output directory is selected!");
					lastResults = "No output directory is selected!";
					logger.warn(lastResults);
				}
			}
			else {
				regSegImgAcqDistProp.set(fileName);
			}
		}		
	}

	
	@Override
	protected void postprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
	
	}
	
	
	@Override
	public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
		return params;
	}

	
	@Override
	public String getName() {
		return "stardist cell nucleus detection";
	}

	
	@Override
	public String getLastResultsDescription() {
		return "";
	}

	
	@Override
	public String getDescription() {
		return "Run stardist cell nucleus detection";
	}

	
	@Override
	protected Collection<PathObject> getParentObjects(ImageData<BufferedImage> imageData) {
		Collection<Class<? extends PathObject>> parentClasses = getSupportedParentObjectClasses();
		List<PathObject> parents = new ArrayList<>();
		for (PathObject parent : imageData.getHierarchy().getSelectionModel().getSelectedObjects()) {
			for (Class<? extends PathObject> cls : parentClasses) {
				if (cls.isAssignableFrom(parent.getClass())) {
					parents.add(parent);
					break;
				}
			}
		}
		
		return parents;
	}

	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(TMACoreObject.class);
		list.add(PathRootObject.class);
		return list;	
	}
}
