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

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.FileFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.opencsv.CSVReader;

import javafx.beans.property.StringProperty;
import javafx.geometry.Point2D;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathROIObject;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */



public class VisiumAnnotation extends AbstractDetectionPlugin<BufferedImage> {
	
	final private static Logger logger = LoggerFactory.getLogger(VisiumAnnotation.class);
	final private StringProperty vsumAntnVsumFldrProp = PathPrefs.createPersistentPreference("vsumAntnVsumFldr", ""); 
	private ParameterList params;

	private final List<String> formatList = List.of("detection", "annotation");
	
	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public VisiumAnnotation() {
		params = new ParameterList()
			.addStringParameter("visiumDir", "Visium directory", vsumAntnVsumFldrProp.get(), "Visium Out Directory")
			.addDoubleParameter("spotDiameter", "Spot Diameter", 65, GeneralTools.micrometerSymbol(), "Spot Diameter")
			.addChoiceParameter("format", "Format", formatList.get(0), formatList, "Format")
			.addBooleanParameter("tissueRegionsOnly", "Tissue regions only?", true, "Tissue regions only?")
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {			
			final List<PathObject> pathObjects = new ArrayList<PathObject>();
			
			pathObjects.addAll(imageData.getHierarchy().getRootObject().getChildObjects());
			
			try {
				final File visiumFileFolder = new File(vsumAntnVsumFldrProp.get());
				final FileFilter spatialTarGzFileFilter = new WildcardFileFilter("*spatial.tar.gz");
				final File[] spatialTarGzFileList = visiumFileFolder.listFiles(spatialTarGzFileFilter);
				assert spatialTarGzFileList.length == 1: "Number of *spatial.tar.gz is wrong.";

				final TarArchiveInputStream spatialTarGzFileStream = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(spatialTarGzFileList[0])));
				TarArchiveEntry spatialTarGzFileStreamEntry = spatialTarGzFileStream.getNextTarEntry();	
				assert spatialTarGzFileStreamEntry != null: "Opening *spatial.tar.gz failed.";
				
				BufferedReader spatialBufferReader = null;
				
				while (spatialTarGzFileStreamEntry != null) {
					spatialBufferReader = new BufferedReader(new InputStreamReader(spatialTarGzFileStream));
					if(spatialTarGzFileStreamEntry.getName().equals("spatial/tissue_positions_list.csv")) break;

					spatialTarGzFileStreamEntry = spatialTarGzFileStream.getNextTarEntry(); // You forgot to iterate to the next file
				}

				assert spatialTarGzFileStreamEntry != null: "spatial/tissue_positions_list.csv failed.";
				
				final CSVReader spatialReader = new CSVReader(spatialBufferReader);
				final HashMap<String, List<Integer>> spatialHMap = new HashMap<String, List<Integer>>();
			     
		        String[] spatgialNextRecord;
		        List<Point2D> posList = new ArrayList<Point2D>();
		        
		        spatialReader.readNext();
		        
		        while ((spatgialNextRecord = spatialReader.readNext()) != null) {
		        	List<Integer> list = new ArrayList<Integer>();
		        	list.add(Integer.parseInt(spatgialNextRecord[1]));
		        	list.add(Integer.parseInt(spatgialNextRecord[2]));
		        	list.add(Integer.parseInt(spatgialNextRecord[3]));
		        	list.add(Integer.parseInt(spatgialNextRecord[4]));
		        	list.add(Integer.parseInt(spatgialNextRecord[5]));
		        	
		        	posList.add(new Point2D(Double.parseDouble(spatgialNextRecord[4]), Double.parseDouble(spatgialNextRecord[5])));
		        	
		        	spatialHMap.put(spatgialNextRecord[0], list);
		        	
//		        	final AtomicDouble minDistPxl = new AtomicDouble(-1.0);
//		        	
//		        	IntStream.range(0, posList.size()).parallel().forEach(i -> {
////	                for(int i = 0; i < posList.size(); i ++) {
//	          
//		        		IntStream.range(0, posList.size()).parallel().forEach(j -> {
////	                    for(int j = 0; j < posList.size(); j ++) {
//	                        final double distPxl = posList.get(i).distance(posList.get(j));
//	                        minDistPxl.set((i != j && (minDistPxl.get() < 0 || distPxl < minDistPxl.get()))? distPxl: minDistPxl.get());
//		        		});
////	                    }
//		        	});
////	                }
		        }
		        
		        spatialReader.close();

		        final FileFilter analysisTarGzFileFilter = new WildcardFileFilter("*analysis.tar.gz");
				final File[] analysisTarGzFileList = visiumFileFolder.listFiles(analysisTarGzFileFilter);
				assert analysisTarGzFileList.length == 1: "Number of *analysis.tar.gz is wrong.";

				final TarArchiveInputStream analysisTarGzFileStream = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(analysisTarGzFileList[0])));
				TarArchiveEntry analysisTarGzFileStreamEntry = analysisTarGzFileStream.getNextTarEntry();	
				assert analysisTarGzFileStreamEntry != null: "Opening *spatial.tar.gz failed.";
				
				BufferedReader analysisBufferReader = null;
				
				while (analysisTarGzFileStreamEntry != null) {
					analysisBufferReader = new BufferedReader(new InputStreamReader(analysisTarGzFileStream));
					if(analysisTarGzFileStreamEntry.getName().equals("analysis/clustering/graphclust/clusters.csv")) break;

					analysisTarGzFileStreamEntry = analysisTarGzFileStream.getNextTarEntry(); // You forgot to iterate to the next file
				}
				
				assert analysisTarGzFileStreamEntry != null: "analysis/clustering/graphclust/clusters.csv failed.";
				
				final ImageServer<BufferedImage> server = imageData.getServer();
		        final double imagePixelSizeMicrons = server.getPixelCalibration().getAveragedPixelSizeMicrons();
		        
	        	final CSVReader clusterReader = new CSVReader(analysisBufferReader);
		        final HashMap<String, Integer> analysisHMap = new HashMap<String, Integer>();

		        String[] clusterNextRecord;
		        int clsNum = 0;
		        while ((clusterNextRecord = clusterReader.readNext()) != null) {
		            try {
		                final Integer cls = Integer.parseInt(clusterNextRecord[1]);
		                clsNum = cls > clsNum? cls: clsNum;
		                analysisHMap.put(clusterNextRecord[0], cls);
		            } catch (NumberFormatException nfe) {}
		        }
		        clusterReader.close();

		        final Color[] palette = new Color[clsNum+1];
	    	    for(int i = 0; i < clsNum+1; i++) palette[i] = Color.getHSBColor((float) i / (float) clsNum+1, 0.85f, 1.0f);
		    			        
		        Set<String> barcodeSet = spatialHMap.keySet();
				final int rad = (int)Math.round(0.5*params.getDoubleParameterValue("spotDiameter")/imagePixelSizeMicrons);
				final int dia = (int)Math.round(params.getDoubleParameterValue("spotDiameter")/imagePixelSizeMicrons);
				
				final HashMap<String, PathObject> spotToPathObjHashMap = new HashMap<>();
				
		        for(String barcode: barcodeSet) {
		        	List<Integer> list = spatialHMap.get(barcode);
		        	
		        	final int in_tissue = list.get(0);
		        	final int pxl_row_in_fullres = (int)Math.round(list.get(3));
		        	final int pxl_col_in_fullres = (int)Math.round(list.get(4));
		        	
		        	if(params.getBooleanParameterValue("tissueRegionsOnly") && (in_tissue == 1) || !params.getBooleanParameterValue("tissueRegionsOnly")) {
		        		final int cluster = analysisHMap.containsKey(barcode)? analysisHMap.get(barcode): 0;
						final String pathObjName = barcode;
						final String pathClsName = String.valueOf(cluster);
						
						ROI pathRoi = ROIs.createEllipseROI(pxl_col_in_fullres-rad, pxl_row_in_fullres-rad, dia, dia, null);
						
				    	final PathClass pathCls = PathClass.fromString(pathClsName);
							
				    	final PathROIObject detObj = params.getChoiceParameterValue("format").equals(formatList.get(0))? 
				    			(PathDetectionObject) PathObjects.createDetectionObject(pathRoi, pathCls):
			    				(PathAnnotationObject) PathObjects.createAnnotationObject(pathRoi, pathCls);
				    	
						detObj.setName(pathObjName);
						detObj.setColor(palette[cluster].getRGB());
				    	
						spotToPathObjHashMap.put(barcode, detObj);
						
						final MeasurementList pathObjMeasList = detObj.getMeasurementList();
						
						pathObjMeasList.close();
						pathObjects.add(detObj);
		        	}
		        }
		         
		        final FileFilter barcodesTarGzFileFilter = new WildcardFileFilter("*filtered_feature_bc_matrix.tar.gz");
				final File[] barcodesTarGzFileList = visiumFileFolder.listFiles(barcodesTarGzFileFilter);
				assert barcodesTarGzFileList.length == 1: "Number of *filtered_feature_bc_matrix.tar.gz is wrong.";

				final TarArchiveInputStream barcodesTarGzFileStream = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(barcodesTarGzFileList[0])));
				TarArchiveEntry barcodesTarGzFileStreamEntry = barcodesTarGzFileStream.getNextTarEntry();	
				assert barcodesTarGzFileStreamEntry != null: "Opening *filtered_feature_bc_matrix.tar.gz failed.";
				
				while (barcodesTarGzFileStreamEntry != null) {
					if(barcodesTarGzFileStreamEntry.getName().equals("filtered_feature_bc_matrix/barcodes.tsv.gz")) break;

					barcodesTarGzFileStreamEntry = barcodesTarGzFileStream.getNextTarEntry(); // You forgot to iterate to the next file
				}
				
				assert barcodesTarGzFileStreamEntry != null: "filtered_feature_bc_matrix/barcodes.tsv.gz failed.";
				
				// Create a ByteArrayOutputStream to read the content from the .gz file
                final ByteArrayOutputStream barcodesByteArrayOutputStream = new ByteArrayOutputStream();

                // Read the content from the .gz file into the ByteArrayOutputStream
                int barcodesCharacter;
                while ((barcodesCharacter = barcodesTarGzFileStream.read()) != -1) {
                	barcodesByteArrayOutputStream.write(barcodesCharacter);
                }
                
                final byte[] barcodesData = barcodesByteArrayOutputStream.toByteArray();
                final ByteArrayInputStream barcodesByteArrayInputStream = new ByteArrayInputStream(barcodesData);
                final GZIPInputStream barcodesGzipStream = new GZIPInputStream(barcodesByteArrayInputStream);
                final BufferedReader barcodeGzipReader = new BufferedReader(new InputStreamReader(barcodesGzipStream));
                final List<String> barcodeList = new ArrayList<>();
				
				String barcodeNextRecord;
				
				while ((barcodeNextRecord = barcodeGzipReader.readLine()) != null) {
					barcodeList.add(barcodeNextRecord);
				}
			
		        final FileFilter featuresTarGzFileFilter = new WildcardFileFilter("*filtered_feature_bc_matrix.tar.gz");
				final File[] featuresTarGzFileList = visiumFileFolder.listFiles(featuresTarGzFileFilter);
				assert featuresTarGzFileList.length == 1: "Number of *filtered_feature_bc_matrix.tar.gz is wrong.";

				final TarArchiveInputStream featuresTarGzFileStream = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(featuresTarGzFileList[0])));
				TarArchiveEntry featuresTarGzFileStreamEntry = featuresTarGzFileStream.getNextTarEntry();	
				assert featuresTarGzFileStreamEntry != null: "Opening *filtered_feature_bc_matrix.tar.gz failed.";
				
				BufferedReader featuresBufferReader = null;
				
				while (featuresTarGzFileStreamEntry != null) {
					featuresBufferReader = new BufferedReader(new InputStreamReader(featuresTarGzFileStream));
					if(featuresTarGzFileStreamEntry.getName().equals("filtered_feature_bc_matrix/features.tsv.gz")) break;
					
					featuresTarGzFileStreamEntry = featuresTarGzFileStream.getNextTarEntry(); // You forgot to iterate to the next file
				}
				
				assert featuresTarGzFileStreamEntry != null: "filtered_feature_bc_matrix/features.tsv.gz failed.";
		        
		        
				// Create a ByteArrayOutputStream to read the content from the .gz file
                final ByteArrayOutputStream featuresByteArrayOutputStream = new ByteArrayOutputStream();

                // Read the content from the .gz file into the ByteArrayOutputStream
                int featuresCharacter;
                while ((featuresCharacter = featuresTarGzFileStream.read()) != -1) {
                	featuresByteArrayOutputStream.write(featuresCharacter);
                }
                
                // Convert the ByteArrayOutputStream to a byte array
                byte[] featuresData = featuresByteArrayOutputStream.toByteArray();

                // Create a ByteArrayInputStream using the byte array
                ByteArrayInputStream featuresByteArrayInputStream = new ByteArrayInputStream(featuresData);

                // Create a GZIPInputStream to decompress the data
                final GZIPInputStream featuresGzipStream = new GZIPInputStream(featuresByteArrayInputStream);		        
                
				final List<String> featureIdList = new ArrayList<>();
				final List<String> featureNameList = new ArrayList<>();
				final List<String> featureTypeList = new ArrayList<>();
				
				final BufferedReader featureGzipReader = new BufferedReader(new InputStreamReader(featuresGzipStream));
				String featureNextRecord;
				while ((featureNextRecord = featureGzipReader.readLine()) != null) {
					final String[] featureNextRecordArray = featureNextRecord.split("\t");
					featureIdList.add(featureNextRecordArray[0]);
					featureNameList.add(featureNextRecordArray[1]);
					featureTypeList.add(featureNextRecordArray[2]);
				}
				
				final FileFilter matrixTarGzFileFilter = new WildcardFileFilter("*filtered_feature_bc_matrix.tar.gz");
				final File[] matrixTarGzFileList = visiumFileFolder.listFiles(matrixTarGzFileFilter);
				assert matrixTarGzFileList.length == 1: "Number of *filtered_feature_bc_matrix.tar.gz is wrong.";

				final TarArchiveInputStream matrixTarGzFileStream = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(matrixTarGzFileList[0])));
				TarArchiveEntry matrixTarGzFileStreamEntry = matrixTarGzFileStream.getNextTarEntry();	
				assert matrixTarGzFileStreamEntry != null: "Opening *filtered_feature_bc_matrix.tar.gz failed.";
				
				BufferedReader matrixBufferReader = null;
				
				while (matrixTarGzFileStreamEntry != null) {
					matrixBufferReader = new BufferedReader(new InputStreamReader(matrixTarGzFileStream));
					if(matrixTarGzFileStreamEntry.getName().equals("filtered_feature_bc_matrix/matrix.mtx.gz")) break;
					
					matrixTarGzFileStreamEntry = matrixTarGzFileStream.getNextTarEntry(); // You forgot to iterate to the next file
				}
				
				assert matrixTarGzFileStreamEntry != null: "filtered_feature_bc_matrix/matrix.mtx.gz failed.";
				
				// Create a ByteArrayOutputStream to read the content from the .gz file
                final ByteArrayOutputStream matrixByteArrayOutputStream = new ByteArrayOutputStream();

                // Read the content from the .gz file into the ByteArrayOutputStream
                int matrixCharacter;
                while ((matrixCharacter = matrixTarGzFileStream.read()) != -1) {
                	matrixByteArrayOutputStream.write(matrixCharacter);
                }
                
                // Convert the ByteArrayOutputStream to a byte array
                final byte[] matrixData = matrixByteArrayOutputStream.toByteArray();

                // Create a ByteArrayInputStream using the byte array
                final ByteArrayInputStream matrixByteArrayInputStream = new ByteArrayInputStream(matrixData);

                // Create a GZIPInputStream to decompress the data
                final GZIPInputStream matrixGzipStream = new GZIPInputStream(matrixByteArrayInputStream);					
                
				final BufferedReader matrixGzipReader = new BufferedReader(new InputStreamReader(matrixGzipStream), '\t');
				matrixGzipReader.readLine();
				matrixGzipReader.readLine();
				matrixGzipReader.readLine();
				
				final int[][] matrix = new int[featureNameList.size()][barcodeList.size()];
				
				String matrixNextRecord;
				while ((matrixNextRecord = matrixGzipReader.readLine()) != null) {
					final String[] matrixNextRecordArray = matrixNextRecord.split(" ");
					final int f = Integer.parseInt(matrixNextRecordArray[0])-1;
					final int b = Integer.parseInt(matrixNextRecordArray[1])-1;
					final int v = Integer.parseInt(matrixNextRecordArray[2]);
					
					matrix[f][b] = v;
				}
		        
		        IntStream.range(0, barcodeList.size()).parallel().forEach(b -> {
					if(spotToPathObjHashMap.containsKey(barcodeList.get(b))) {
				    	final PathObject c = spotToPathObjHashMap.get(barcodeList.get(b));
				    	final MeasurementList pathObjMeasList = c.getMeasurementList();
				    	
				    	for(int f = 0; f < featureNameList.size(); f ++) {	
							pathObjMeasList.put("transcript:"+featureNameList.get(f), matrix[f][b]); 
				    	}
				    	
				    	pathObjMeasList.close();
					}
				}); 			
				
//				final ImageServer<BufferedImage> server = imageData.getServer();
//	        	final String spatialFilePath = java.nio.file.Paths.get(vsumAntnVsumFldrProp.get(), "spatial", "tissue_positions.csv").toString();				
//		        final FileReader spatialFileReader = new FileReader(spatialFilePath);
//		        final CSVReader spatialReader = new CSVReader(spatialFileReader);
//		        final HashMap<String, List<Integer>> spatialHMap = new HashMap<String, List<Integer>>();
//		     
//		        String[] spatgialNextRecord;
//		        List<Point2D> posList = new ArrayList<Point2D>();
//		        
//		        spatialReader.readNext();
//		        
//		        while ((spatgialNextRecord = spatialReader.readNext()) != null) {
//		        	List<Integer> list = new ArrayList<Integer>();
//		        	list.add(Integer.parseInt(spatgialNextRecord[1]));
//		        	list.add(Integer.parseInt(spatgialNextRecord[2]));
//		        	list.add(Integer.parseInt(spatgialNextRecord[3]));
//		        	list.add(Integer.parseInt(spatgialNextRecord[4]));
//		        	list.add(Integer.parseInt(spatgialNextRecord[5]));
//		        	
//		        	posList.add(new Point2D(Double.parseDouble(spatgialNextRecord[4]), Double.parseDouble(spatgialNextRecord[5])));
//		        	
//		        	spatialHMap.put(spatgialNextRecord[0], list);
//		        }
//		        
//		        spatialReader.close();
		        
//		        double minDistPxl = -1;
//
//                for(int i = 0; i < posList.size(); i ++) {
//                    for(int j = 0; j < posList.size(); j ++) {
//                        final double distPxl = posList.get(i).distance(posList.get(j));
//                        minDistPxl = (i != j && (minDistPxl < 0 || distPxl < minDistPxl))? distPxl: minDistPxl;
//                    }
//                }
                
//				final ImageServer<BufferedImage> server = imageData.getServer();
//				
//		        final double imagePixelSizeMicrons = server.getPixelCalibration().getAveragedPixelSizeMicrons();
//		        
//	        	final String clusterFilePath = java.nio.file.Paths.get(vsumAntnVsumFldrProp.get(), "analysis", "clustering", "gene_expression_graphclust", "clusters.csv").toString();
//	        	final FileReader clusterFileReader = new FileReader(clusterFilePath);
//		        final CSVReader clusterReader = new CSVReader(clusterFileReader);
//		        final HashMap<String, Integer> analysisHMap = new HashMap<String, Integer>();
//
//		        String[] clusterNextRecord;
//		        int clsNum = 0;
//		        while ((clusterNextRecord = clusterReader.readNext()) != null) {
//		            try {
//		                final Integer cls = Integer.parseInt(clusterNextRecord[1]);
//		                clsNum = cls > clsNum? cls: clsNum;
//		                analysisHMap.put(clusterNextRecord[0], cls);
//		            } catch (NumberFormatException nfe) {}
//		        }
//		        clusterReader.close();
//
//		        final Color[] palette = new Color[clsNum+1];
//	    	    for(int i = 0; i < clsNum+1; i++) palette[i] = Color.getHSBColor((float) i / (float) clsNum+1, 0.85f, 1.0f);
//		    			        
//		        Set<String> barcodeSet = spatialHMap.keySet();
//				final int rad = (int)Math.round(0.5*params.getDoubleParameterValue("spotDiameter")/imagePixelSizeMicrons);
//				final int dia = (int)Math.round(params.getDoubleParameterValue("spotDiameter")/imagePixelSizeMicrons);
//				
//				final HashMap<String, PathObject> spotToPathObjHashMap = new HashMap<>();
//				
//		        for(String barcode: barcodeSet) {
//		        	List<Integer> list = spatialHMap.get(barcode);
//		        	
//		        	final int in_tissue = list.get(0);
//		        	final int pxl_row_in_fullres = (int)Math.round(list.get(3));
//		        	final int pxl_col_in_fullres = (int)Math.round(list.get(4));
//		        	
//		        	if(params.getBooleanParameterValue("tissueRegionsOnly") && (in_tissue == 1) || !params.getBooleanParameterValue("tissueRegionsOnly")) {
//		        		final int cluster = analysisHMap.containsKey(barcode)? analysisHMap.get(barcode): 0;
//						final String pathObjName = barcode;
//						final String pathClsName = String.valueOf(cluster);
//						
//						ROI pathRoi = ROIs.createEllipseROI(pxl_col_in_fullres-rad, pxl_row_in_fullres-rad, dia, dia, null);
//						
//				    	final PathClass pathCls = PathClass.fromString(pathClsName);
//							
//				    	final PathROIObject detObj = params.getChoiceParameterValue("format").equals(formatList.get(0))? 
//				    			(PathDetectionObject) PathObjects.createDetectionObject(pathRoi, pathCls):
//			    				(PathAnnotationObject) PathObjects.createAnnotationObject(pathRoi, pathCls);
//				    	
//						detObj.setName(pathObjName);
//						detObj.setColor(palette[cluster].getRGB());
//				    	
//						spotToPathObjHashMap.put(barcode, detObj);
//						
//						final MeasurementList pathObjMeasList = detObj.getMeasurementList();
//						
//						pathObjMeasList.close();
//						pathObjects.add(detObj);
//		        	}
//		        }
//		        
//		        final String barcodeFilePath = java.nio.file.Paths.get(vsumAntnVsumFldrProp.get(), "filtered_feature_bc_matrix", "barcodes.tsv.gz").toString();
//				final String featureFilePath = java.nio.file.Paths.get(vsumAntnVsumFldrProp.get(), "filtered_feature_bc_matrix", "features.tsv.gz").toString();
//				final String matrixFilePath = java.nio.file.Paths.get(vsumAntnVsumFldrProp.get(), "filtered_feature_bc_matrix", "matrix.mtx.gz").toString();
//				
//				final GZIPInputStream barcodeGzipStream = new GZIPInputStream(new FileInputStream(barcodeFilePath));
//				try (BufferedReader barcodeGzipReader = new BufferedReader(new InputStreamReader(barcodeGzipStream))) {
//					final List<String> barcodeList = new ArrayList<>();
//					
//					String barcodeNextRecord;
//					while ((barcodeNextRecord = barcodeGzipReader.readLine()) != null) {
//						barcodeList.add(barcodeNextRecord);
//					}
//					
//					final List<String> featureIdList = new ArrayList<>();
//					final List<String> featureNameList = new ArrayList<>();
//					final List<String> featureTypeList = new ArrayList<>();
//					
//					final GZIPInputStream featureGzipStream = new GZIPInputStream(new FileInputStream(featureFilePath));
//					try (BufferedReader featureGzipReader = new BufferedReader(new InputStreamReader(featureGzipStream))) {
//						String featureNextRecord;
//						while ((featureNextRecord = featureGzipReader.readLine()) != null) {
//							final String[] featureNextRecordArray = featureNextRecord.split("\t");
//							featureIdList.add(featureNextRecordArray[0]);
//							featureNameList.add(featureNextRecordArray[1]);
//							featureTypeList.add(featureNextRecordArray[2]);
//						}
//					}
//					
//					final GZIPInputStream matrixGzipStream = new GZIPInputStream(new FileInputStream(matrixFilePath));
//					try (BufferedReader matrixGzipReader = new BufferedReader(new InputStreamReader(matrixGzipStream), '\t')) {
//						matrixGzipReader.readLine();
//						matrixGzipReader.readLine();
//						matrixGzipReader.readLine();
//						
//						final int[][] matrix = new int[featureNameList.size()][barcodeList.size()];
//						
//						String matrixNextRecord;
//						while ((matrixNextRecord = matrixGzipReader.readLine()) != null) {
//							final String[] matrixNextRecordArray = matrixNextRecord.split(" ");
//							final int f = Integer.parseInt(matrixNextRecordArray[0])-1;
//							final int b = Integer.parseInt(matrixNextRecordArray[1])-1;
//							final int v = Integer.parseInt(matrixNextRecordArray[2]);
//							
//							matrix[f][b] = v;
//						}
//				        
//				        IntStream.range(0, barcodeList.size()).parallel().forEach(b -> {
//							if(spotToPathObjHashMap.containsKey(barcodeList.get(b))) {
//						    	final PathObject c = spotToPathObjHashMap.get(barcodeList.get(b));
//						    	final MeasurementList pathObjMeasList = c.getMeasurementList();
//						    	
//						    	for(int f = 0; f < featureNameList.size(); f ++) {	
//									pathObjMeasList.put("transcript:"+featureNameList.get(f), matrix[f][b]); 
//						    	}
//						    	
//						    	pathObjMeasList.close();
//							}
//						}); 
//					}
//				}
			}
			catch(Exception e) {	
				Dialogs.showErrorMessage("Error", e.getMessage());
				lastResults =  e.getMessage();
				logger.error(e.getMessage());				
			}
			
			if (Thread.currentThread().isInterrupted())
				return null;
			
			if (pathObjects == null || pathObjects.isEmpty())
				lastResults =  "No regions detected!";
			else if (pathObjects.size() == 1)
				lastResults =  "1 region detected";
			else
				lastResults =  pathObjects.size() + " regions detected";
			
			logger.info(lastResults);
			
			return pathObjects;
		}
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
	}
	
	
	@Override
	protected void preprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		if(params.getStringParameterValue("visiumDir").isBlank()) {
		
			final File vsumDir = Dialogs.promptForDirectory("Visium directory", new File(vsumAntnVsumFldrProp.get()));
			
			if (vsumDir != null) {
				vsumAntnVsumFldrProp.set(vsumDir.toString());
			}
			else {
				Dialogs.showErrorMessage("Warning", "No Visium directory is selected!");
				lastResults =  "No Visium directory is selected!";
				logger.warn(lastResults);
			}
		}
		else {
			vsumAntnVsumFldrProp.set(params.getStringParameterValue("visiumDir"));
		}
	};
	
	
	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		// boolean micronsKnown = imageData.getServer().getPixelCalibration().hasPixelSizeMicrons();
		// params.setHiddenParameters(!micronsKnown, "requestedPixelSizeMicrons", "minAreaMicrons", "maxHoleAreaMicrons");
		// params.setHiddenParameters(micronsKnown, "requestedDownsample", "minAreaPixels", "maxHoleAreaPixels");
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
		tasks.add(DetectionPluginTools.createRunnableTask(new AnnotationLoader(), getParameterList(imageData), imageData, parentObject));
	}

	@Override
	protected Collection<? extends PathObject> getParentObjects(final ImageData<BufferedImage> imageData) {
		
		PathObjectHierarchy hierarchy = imageData.getHierarchy();
		if (hierarchy.getTMAGrid() == null)
			return Collections.singleton(hierarchy.getRootObject());
		
		return hierarchy.getSelectionModel().getSelectedObjects().stream().filter(p -> p.isTMACore()).collect(Collectors.toList());
	}

	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		// TODO: Re-allow taking an object as input in order to limit bounds
		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(TMACoreObject.class);

		list.add(PathRootObject.class);
		return list;
	}
}
