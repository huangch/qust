/*-
* #%L
* ST-AnD is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as
* published by the Free Software Foundation, either version 3 of the
* License, or (at your option) any later version.
* 
* ST-AnD is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
* 
* You should have received a copy of the GNU General Public License 
* along with ST-AnD.  If not, see <https://www.gnu.org/licenses/>.
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
import java.nio.file.Files;
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
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;
//import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import hdf.hdf5lib.HDF5Constants;
import hdf.hdf5lib.H5;

/**
 * Sampling images of detected objects and store into the specific folder.
 * 
 * @author Chao Hui Huang
 *
 */
public class ObjectClassificationImageAcquisition extends AbstractDetectionPlugin<BufferedImage> {

	private static Logger logger = LoggerFactory.getLogger(ObjectClassificationImageAcquisition.class);

	private ParameterList params;
	private String lastResults = null;

	private static StringProperty objClsImgAcqDistProp = PathPrefs.createPersistentPreference("objClsImgAcqDist", "");
	private static DoubleProperty objClsImgAcqMPPProp = PathPrefs.createPersistentPreference("objClsImgAcqMPP",0.21233);
	private static IntegerProperty objClsImgAcqSamplingSizeProp = PathPrefs.createPersistentPreference("objClsImgAcqSamplingSize", 36);
	private static BooleanProperty objClsImgAcqAllSamplesProp = PathPrefs.createPersistentPreference("objClsImgAcqAllSamplesProp", true);
	private static IntegerProperty objClsImgAcqSamplingNumProp = PathPrefs.createPersistentPreference("objClsImgAcqSamplingNum", 0);
	private static QuSTSetup qustSetup = QuSTSetup.getInstance();
	
	/**
	 * Constructor.
	 */
	public ObjectClassificationImageAcquisition() {
		params = new ParameterList()
				.addStringParameter("dist", "Distination", objClsImgAcqDistProp.get(), "Distination")
				.addChoiceParameter("format", "Format ", "Folder", Arrays.asList("Folder", "WebDataset", "HDF5"), "Choose which format to use")
	        	.addEmptyParameter("").addEmptyParameter("Reampling using...")
				.addBooleanParameter("normalization", "Normalization (default: yes)", true, "Normalization (default: no)")
				.addBooleanParameter("dontResampling", "Do not rescaling image (default: yes)", true, "Do not rescaling image (default: yes)")
				.addEmptyParameter("...or...")
				.addDoubleParameter("MPP", "pixel size", objClsImgAcqMPPProp.get(), GeneralTools.micrometerSymbol(), "Pixel Size")
				.addEmptyParameter("")
				.addIntParameter("samplingSize", "Sampling Size", objClsImgAcqSamplingSizeProp.get(), "pixel(s)", "Sampling Size")
				.addBooleanParameter("allSamples", "Include all samples (default: yes)", objClsImgAcqAllSamplesProp.get(), "Include all samples? (default: yes)")
				.addEmptyParameter("...or...")
				.addIntParameter("samplingNum", "Maximal Sampling Number", objClsImgAcqSamplingNumProp.get(), "objects(s)", "Maximal Sampling Number")
				;
	}

	
	class DetectedObjectImageSampling implements ObjectDetector<BufferedImage> {

		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			
			objClsImgAcqMPPProp.set(params.getDoubleParameterValue("MPP"));
			objClsImgAcqSamplingSizeProp.set(params.getIntParameterValue("samplingSize"));
			objClsImgAcqAllSamplesProp.set(params.getBooleanParameterValue("allSamples"));
			objClsImgAcqSamplingNumProp.set(params.getIntParameterValue("samplingNum"));
			   
			String saveType = (String)params.getChoiceParameterValue("format");
			
			
			List<PathObject> selectedAnnotationPathObjectList = Collections
					.synchronizedList(hierarchy.getSelectionModel().getSelectedObjects().stream()
							.filter(e -> e.isAnnotation()).collect(Collectors.toList()));

			if (selectedAnnotationPathObjectList.isEmpty()) {
				logger.error("Missed selected annotations");
				return hierarchy.getAnnotationObjects();
			}
			
			ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
			String serverPath = server.getPath();

			double imageMPP = server.getPixelCalibration().getAveragedPixelSizeMicrons();
			double scalingFactor = params.getBooleanParameterValue("dontResampling") ? 1.0: params.getDoubleParameterValue("MPP") / imageMPP;
			int samplingFeatureSize = (int) (0.5 + scalingFactor * params.getIntParameterValue("samplingSize"));
			
			List<PathObject> allPathObjects = Collections.synchronizedList(new ArrayList<PathObject>());
			
			selectedAnnotationPathObjectList.stream().forEach(p -> {
				allPathObjects.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()));
			});

			Collections.shuffle(allPathObjects);

			QuSTSetup QuSTOptions = QuSTSetup.getInstance();
			int normalizationSampleSize = QuSTOptions.getNormalizationSampleSize();
			
			if (allPathObjects.size() < normalizationSampleSize) {
				logger.error("Object number is smaller than the required number for normalization");
				return hierarchy.getAnnotationObjects();
			}
			
			List<PathObject> normalizationSamplingPathObjects = Collections
					.synchronizedList(allPathObjects.subList(0, normalizationSampleSize));
			
			List<BufferedImage> normalizationSamplingImageList = Collections.synchronizedList(new ArrayList<>());
					
			AtomicBoolean success = new AtomicBoolean(true);
			
			IntStream.range(0, normalizationSamplingPathObjects.size()).parallel().forEach(i -> {
				if(!success.get()) return;
				
				PathObject objObject = normalizationSamplingPathObjects.get(i);
				ROI objRoi = objObject.getROI();

				int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double) samplingFeatureSize / 2.0));
				int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double) samplingFeatureSize / 2.0));
				RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0,
						samplingFeatureSize, samplingFeatureSize);

				try {
					BufferedImage imgContent;
					imgContent = (BufferedImage) server.readRegion(objRegion);
					BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
					imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);
					normalizationSamplingImageList.add(imgBuf);
				} catch (IOException e) {
					e.printStackTrace();
					success.set(false);
					return;
				}
			});
			
			if (!success.get()) {
				logger.error("Collection data for computing normalization failed!");
				return hierarchy.getAnnotationObjects();
			}
			
			BufferedImage normalizationSamplingImage = MacenkoStainingNormalizer.concatBufferedImages(normalizationSamplingImageList);
			double[][][] normalizationSamplingImageRGBAry = MacenkoStainingNormalizer.convertImageToRGBArray(normalizationSamplingImage);
			double[][][] normalizationSamplingImageSDAAry = MacenkoStainingNormalizer.rgbToSda(normalizationSamplingImageRGBAry, 255, false);
			double[][] est_W = MacenkoStainingNormalizer.separateStainsMacenkoPca(normalizationSamplingImageSDAAry);
					
					
			int samplingNum = params.getIntParameterValue("samplingNum") == 0
					|| params.getIntParameterValue("samplingNum") > allPathObjects.size() 
					|| params.getBooleanParameterValue("allSamples") 
					? allPathObjects.size()
							: params.getIntParameterValue("samplingNum");
					
			List<PathObject> samplingPathObjects = Collections
					.synchronizedList(allPathObjects.subList(0, samplingNum));

			Path locationPath = Paths.get(objClsImgAcqDistProp.get());
			String fileFmt = qustSetup.getImageFileFormat().strip();
			String fileExt = fileFmt.charAt(0) == '.' ? fileFmt.substring(1) : fileFmt;

			if (Files.exists(locationPath)) {
				new File(locationPath.toString()).delete();
			}
			
			if (saveType == "Folder") {
				new File(locationPath.toString()).mkdirs();
					
				IntStream.range(0, samplingPathObjects.size()).parallel().forEach(i -> {
					if(!success.get()) return;
					
					PathObject objObject = samplingPathObjects.get(i);
					ROI objRoi = objObject.getROI();

					int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double) samplingFeatureSize / 2.0));
					int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double) samplingFeatureSize / 2.0));
					RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0,
							samplingFeatureSize, samplingFeatureSize);

					BufferedImage imgContent = null;
					
					try {
						imgContent = (BufferedImage) server.readRegion(objRegion);
					
						BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
						imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);
						
						BufferedImage outputImgBuf = null;
						
			            if (getParameterList(imageData).getBooleanParameterValue("normalization")) {
			            	double[][][] imgBufRGBAry = MacenkoStainingNormalizer.convertImageToRGBArray(imgBuf);
			            	double[][][] outputImgBufAry = MacenkoStainingNormalizer.deconvolutionBasedNormalization(
			            			imgBufRGBAry, 
			    	    			est_W,  
			    	    			MacenkoStainingNormalizer.targetStainReferenceMatrix, 
			    	    			null, 
			    	    			null, 
			    	    			null,
			    	    			null
			    	    		);
			            	outputImgBuf = MacenkoStainingNormalizer.convertRGBArrayToImage(outputImgBufAry, BufferedImage.TYPE_3BYTE_BGR);
			            } else {
			            	outputImgBuf = imgBuf;
			            }
			            
						BufferedImage imgSampled = params.getBooleanParameterValue("dontResampling") ? outputImgBuf
								: Scalr.resize(outputImgBuf, params.getIntParameterValue("samplingSize"));
						
						Path imageFilePath = Paths.get(locationPath.toString(), objObject.getID().toString() + "." + fileExt);
						File imageFile = new File(imageFilePath.toString());
						
						ImageIO.write(imgSampled, fileExt, imageFile);
					} catch (Exception e) {
						e.printStackTrace();
						success.set(false);
						return;
					}
				 });
				
				if (!success.get()) {
					logger.error("Extracting dataset failed!");
					return hierarchy.getAnnotationObjects();
				}
			} else if (saveType == "WebDataset") {
				
				class ImageLabelPair {
				   String idx;
				   byte[] imgBytes;
				   byte[] labelBytes;
				   ImageLabelPair(String idx, byte[] imgBytes, byte[] labelBytes) {
					   this.idx = idx;
				       this.imgBytes = imgBytes;
				       this.labelBytes = labelBytes;
				   }
				}
						
				int batchSize = 1000;
				int batchNum = 0;
				Iterator<PathObject> samplingPathObjectIterator = samplingPathObjects.iterator();
				
		        // Write this batch to tar
        		String tarFilename = locationPath.toString();
        		try (TarArchiveOutputStream tarOut = new TarArchiveOutputStream(new BufferedOutputStream(new FileOutputStream(tarFilename)))) {
        			while (samplingPathObjectIterator.hasNext()) {
		        		List<BufferedImage> imageBatch = new ArrayList<>(batchSize);
		        		List<String> labelBatch = new ArrayList<>(batchSize);
		        		List<String> indexBatch = new ArrayList<>(batchSize);
		        		// Load next batch (avoiding OOM)
						
		        		for (int i = 0; i < batchSize && samplingPathObjectIterator.hasNext(); i++) {
		        			PathObject objObject = samplingPathObjectIterator.next();
		        			
		        			ROI objRoi = objObject.getROI();
		        			int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double) samplingFeatureSize / 2.0));
		        			int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double) samplingFeatureSize / 2.0));
		        			RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0,
		        					samplingFeatureSize, samplingFeatureSize);
					
		        			BufferedImage imgContent = (BufferedImage) server.readRegion(objRegion);
		        			BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
			        	   
		        			imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);
		        			
		        			BufferedImage outputImgBuf = null;
		                    
				            if (getParameterList(imageData).getBooleanParameterValue("normalization")) {
				            	double[][][] imgBufRGBAry = MacenkoStainingNormalizer.convertImageToRGBArray(imgBuf);
				            	double[][][] outputImgBufAry = MacenkoStainingNormalizer.deconvolutionBasedNormalization(
				            			imgBufRGBAry, 
				    	    			est_W,  
				    	    			MacenkoStainingNormalizer.targetStainReferenceMatrix, 
				    	    			null, 
				    	    			null, 
				    	    			null,
				    	    			null
				    	    		);
				            	outputImgBuf = MacenkoStainingNormalizer.convertRGBArrayToImage(outputImgBufAry, BufferedImage.TYPE_3BYTE_BGR);
				            } else {
				            	outputImgBuf = imgBuf;
				            }
		                    
		                    
		        			BufferedImage imgSampled = params.getBooleanParameterValue("dontResampling") ? outputImgBuf
		        					: Scalr.resize(outputImgBuf, params.getIntParameterValue("samplingSize"));
	
			        	    imageBatch.add(imgSampled);
			        	    labelBatch.add(objObject.getPathClass().toString());
			        	    indexBatch.add(objObject.getID().toString());
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
			                    return new ImageLabelPair(indexBatch.get(i), imgBytes, labelBytes);
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
	        			
		        		logger.info(String.format("Wrote batch %d to %s", batchNum, tarFilename));
		        		batchNum++;
		        		// Release memory for next batch
		        		imageBatch.clear();
		        		labelBatch.clear();
		        		encodedBatch.clear();
		        		System.gc(); // optional, may help with memory	        			
	        		}
        		}
			} else if (saveType == "HDF5") {
				String osName = System.getProperty("os.name").toLowerCase();
				String libResourcePath = "/native/libhdf5_java.so";
				
				if (osName.contains("nix") || osName.contains("nux")) {
					libResourcePath = "/native/linux/libhdf5_java.so";
				} else {
					logger.error("Unsupported OS: "+ osName);
					return hierarchy.getAnnotationObjects();
				}
				
				InputStream in = ObjectClassificationImageAcquisition.class.getResourceAsStream(libResourcePath);
				if (in == null) {
					logger.error("Native library not found: "+libResourcePath);
					return hierarchy.getAnnotationObjects();
				}
					
				File temp = File.createTempFile("libhdf5_java", ".so");
				temp.deleteOnExit();
				
				try (OutputStream out = new FileOutputStream(temp)) {
					byte[] buf = new byte[4096];
					int len;
					while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
				}
				System.load(temp.getAbsolutePath());
				
				logger.info("java_hdf5 native library loaded successfully!");
				
				int StrLenCnt = 0;
				
				for(PathObject s: samplingPathObjects) {
					String id = s.getID().toString();
					String label = s.getPathClass().toString();
					byte[] batchIdUtf = id.getBytes(StandardCharsets.UTF_8);
					byte[] batchLabelUtf = label.getBytes(StandardCharsets.UTF_8);
					 
					if (StrLenCnt < batchIdUtf.length) StrLenCnt = batchIdUtf.length;
					if (StrLenCnt < batchLabelUtf.length) StrLenCnt = batchLabelUtf.length;
				}
				
				final int maxStrLen = StrLenCnt;
				int batchSize = 1000;
				
				// Create HDF5 file
				long file_id = H5.H5Fcreate(locationPath.toString(), HDF5Constants.H5F_ACC_TRUNC,
						HDF5Constants.H5P_DEFAULT, HDF5Constants.H5P_DEFAULT);
				// Create image dataset
				long[] chunk_dims = {batchSize, samplingFeatureSize, samplingFeatureSize, 3};
				long[] max_dims = {HDF5Constants.H5S_UNLIMITED, samplingFeatureSize, samplingFeatureSize, 3};
				long[] init_dims = {0, samplingFeatureSize, samplingFeatureSize, 3};
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
				
				while (offset < samplingPathObjects.size()) {
					int end = Math.min(offset + batchSize, samplingPathObjects.size());
					int B = end - offset;
					byte[][][][] batchImageBuf = new byte[B][samplingFeatureSize][samplingFeatureSize][3];
					byte[][] batchIdBuf = new byte[B][maxStrLen];
					byte[][] batchLabelBuf = new byte[B][maxStrLen];
					final int final_offset = offset;
					IntStream.range(0, B).parallel().forEach(i -> {
						if (!success.get()) return;
						
        				PathObject objObject = samplingPathObjects.get(final_offset + i);
	        			
	        			ROI objRoi = objObject.getROI();
	        			int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double) samplingFeatureSize / 2.0));
	        			int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double) samplingFeatureSize / 2.0));
	        			RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0,
	        					samplingFeatureSize, samplingFeatureSize);
	        			
        				BufferedImage imgContent = null;
        				
        				try {
        					imgContent = (BufferedImage) server.readRegion(objRegion);
						} catch (Exception e) {
							e.printStackTrace();
							success.set(false);
							return;
						}
        					
	        			BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
		        	   
	        			imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);
	        			BufferedImage outputImgBuf = null;
	                    
			            if (getParameterList(imageData).getBooleanParameterValue("normalization")) {
			            	double[][][] imgBufRGBAry = MacenkoStainingNormalizer.convertImageToRGBArray(imgBuf);
			            	double[][][] outputImgBufAry = MacenkoStainingNormalizer.deconvolutionBasedNormalization(
			            			imgBufRGBAry, 
			    	    			est_W,  
			    	    			MacenkoStainingNormalizer.targetStainReferenceMatrix, 
			    	    			null, 
			    	    			null, 
			    	    			null,
			    	    			null
			    	    		);
			            	outputImgBuf = MacenkoStainingNormalizer.convertRGBArrayToImage(outputImgBufAry, BufferedImage.TYPE_3BYTE_BGR);
			            } else {
			            	outputImgBuf = imgBuf;
			            }
	        			
	        			
	        			BufferedImage imgSampled = params.getBooleanParameterValue("dontResampling") ? 
	        					outputImgBuf: Scalr.resize(outputImgBuf, params.getIntParameterValue("samplingSize"));
	        			
	        			IntStream.range(0, samplingFeatureSize).parallel().forEach(y -> {
//						for (int y = 0; y < samplingFeatureSize; y++) {
	        				IntStream.range(0, samplingFeatureSize).parallel().forEach( x -> {
							// for (int x = 0; x < samplingFeatureSize; x++) {
								int rgb = imgSampled.getRGB(x, y);
								batchImageBuf[i][y][x][0] = (byte) ((rgb >> 16) & 0xFF);
								batchImageBuf[i][y][x][1] = (byte) ((rgb >> 8) & 0xFF);
								batchImageBuf[i][y][x][2] = (byte) (rgb & 0xFF);
							});
						});
						
						String batchId = objObject.getID().toString();
						byte[] batchIdUtfBytes = batchId.getBytes(StandardCharsets.UTF_8);
						int batchIdLen = Math.min(batchIdUtfBytes.length, maxStrLen - 1);
						System.arraycopy(batchIdUtfBytes, 0, batchIdBuf[i], 0, batchIdLen);
						batchIdBuf[i][batchIdLen] = 0;
						
						String  batchLabel = objObject.getPathClass().toString();
						byte[] batchLabelUtfBytes = batchLabel.getBytes(StandardCharsets.UTF_8);
						int batchLabelLen = Math.min(batchLabelUtfBytes.length, maxStrLen - 1);
						System.arraycopy(batchLabelUtfBytes, 0, batchLabelBuf[i], 0, batchLabelLen);
						batchLabelBuf[i][batchLabelLen] = 0;
					});
					
					if (!success.get()) {
						logger.error("error");
						return hierarchy.getAnnotationObjects();
					}
						
					long newTotal = offset + B;
					// Extend datasets
					H5.H5Dset_extent(image_dset_id, new long[]{newTotal, samplingFeatureSize, samplingFeatureSize, 3});
					H5.H5Dset_extent(id_dset, new long[]{newTotal});
					H5.H5Dset_extent(label_dset, new long[]{newTotal});
					// Write image batch
					long img_space = H5.H5Dget_space(image_dset_id);
					H5.H5Sselect_hyperslab(img_space, HDF5Constants.H5S_SELECT_SET,
							new long[]{offset, 0, 0, 0}, null, new long[]{B, samplingFeatureSize, samplingFeatureSize, 3}, null);
					long img_mem = H5.H5Screate_simple(4, new long[]{B, samplingFeatureSize, samplingFeatureSize, 3}, null);
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
			
			return hierarchy.getAnnotationObjects();
		}
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
	}
	
	@Override
	protected void preprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
		String fileName = params.getStringParameterValue("dist");
		String format = (String)params.getChoiceParameterValue("format");
		File objClsImgAcqDistFp;
		
		
		
		
//		try {
//			File pngfile = new File("/workspace/qupath-docker/qupath/qust_scripts/hne_example.png");
//			BufferedImage pngexample = ImageIO.read(pngfile);
//			double[][][] pngexampleRGBAry = MacenkoStainingNormalizer.convertImageToRGBArray(pngexample);
//			double[][][] pngexampleSDAAry = MacenkoStainingNormalizer.rgbToSda(pngexampleRGBAry, 255, false);
//			double[][] pngexampleAry_W = MacenkoStainingNormalizer.separateStainsMacenkoPca(pngexampleSDAAry);
//	        double[][][] outputImgBufAry = MacenkoStainingNormalizer.deconvolutionBasedNormalization(
//	        		pngexampleRGBAry, 
//	    			pngexampleAry_W,  
//	    			MacenkoStainingNormalizer.targetStainReferenceMatrix, 
//	    			null, 
//	    			null, // new String[]{"hematoxylin", "eosin", "null"},
//	    			null,
//	    			null
//	    		);
//	    	
//	        BufferedImage outputImgBuf = MacenkoStainingNormalizer.convertRGBArrayToImage(outputImgBufAry, BufferedImage.TYPE_3BYTE_BGR);
//	        File rstfile = new File("/workspace/qupath-docker/qupath/qust_scripts/hne_example_rst.png");
//			
//	        ImageIO.write(outputImgBuf, "png", rstfile);
//		} catch (IOException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
		
		
        
        
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
				
				objClsImgAcqDistFp = FileChoosers.promptToSaveFile("Export to file", defaultFile,
						FileChoosers.createExtensionFilter("WebDataset file", ".tar"));
				
				if (objClsImgAcqDistFp != null) {
					objClsImgAcqDistProp.set(objClsImgAcqDistFp.toString());
				} else {
//					Dialogs.showErrorMessage("Warning", "No output file is selected!");
					lastResults = "No output file is selected!";
					logger.warn(lastResults);
				}
			}
			else {
				objClsImgAcqDistProp.set(fileName);
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
				
				objClsImgAcqDistFp = FileChoosers.promptToSaveFile("Export to file", defaultFile,
						FileChoosers.createExtensionFilter("HDF5 file", ".h5"));
				
				if (objClsImgAcqDistFp != null) {
					objClsImgAcqDistProp.set(objClsImgAcqDistFp.toString());
				} else {
//					Dialogs.showErrorMessage("Warning", "No output file is selected!");
					lastResults = "No output file is selected!";
					logger.warn(lastResults);
				}
			}
			else {
				objClsImgAcqDistProp.set(fileName);
			}
		} else if(format == "Folder") {
			if (fileName.isBlank()) {
				objClsImgAcqDistFp = FileChoosers.promptForDirectory("Output directory", new File(objClsImgAcqDistProp.get()));
	
				if (objClsImgAcqDistFp != null) {
					objClsImgAcqDistProp.set(objClsImgAcqDistFp.toString());
				} else {
//					Dialogs.showErrorMessage("Warning", "No output directory is selected!");
					lastResults = "No output directory is selected!";
					logger.warn(lastResults);
				}
			}
			else {
				objClsImgAcqDistProp.set(fileName);
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
		return "Simple tissue detection";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}

	@Override
	public String getDescription() {
		return "Detect one or more regions of interest by applying a global threshold";
	}

	@Override
	protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
		tasks.add(DetectionPluginTools.createRunnableTask(new DetectedObjectImageSampling(), getParameterList(imageData), imageData, parentObject));
	}

	@Override
	protected Collection<? extends PathObject> getParentObjects(ImageData<BufferedImage> imageData) {

		PathObjectHierarchy hierarchy = imageData.getHierarchy();
		if (hierarchy.getTMAGrid() == null)
			return Collections.singleton(hierarchy.getRootObject());

		return hierarchy.getSelectionModel().getSelectedObjects().stream().filter(p -> p.isTMACore())
				.collect(Collectors.toList());
	}

	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(PathAnnotationObject.class);
		list.add(TMACoreObject.class);
		list.add(PathRootObject.class);
		return list;		
	}
}
