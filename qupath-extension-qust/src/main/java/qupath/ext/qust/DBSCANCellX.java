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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.scripting.QPEx;
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
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVWriter;
/**
 * Plugin for DBSCAN-CellX
 * 
 * @author Chao Hui Huang
 *
 */
public class DBSCANCellX extends AbstractDetectionPlugin<BufferedImage> {
	
	final private static Logger logger = LoggerFactory.getLogger(DBSCANCellX.class);
	
	private ParameterList params;
	
	private String lastResults = null;
	private static QuSTSetup qustSetup = QuSTSetup.getInstance();
	private static final AtomicInteger hackDigit = new AtomicInteger(0);
	
	/**
	 * Constructor.
	 * @throws Exception 
	 */
	public DBSCANCellX() throws Exception {
		final PathObjectHierarchy hierarchy = QP.getCurrentImageData().getHierarchy();
		
        final int sltdAnnotNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isAnnotation()).collect(Collectors.toList()).size();
        final int sltdDetNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()).size();
        
        if(sltdAnnotNum > 0 && sltdDetNum > 0) {
        	Dialogs.showErrorMessage("Error", "Do not select both annotations and detections.");
        	throw new Exception("Do not select both annotations and detections.");
        }
        else if(sltdAnnotNum == 0 && sltdDetNum == 0) {
        	Dialogs.showErrorMessage("Error", "No annotations/detections are selected.");
        	throw new Exception("No annotations/detections are selected.");
        }
        
        params = new ParameterList()
			.addTitleParameter("DBSCAN-CellX Analysis")		
			.addBooleanParameter("edgeMode", "Edge mode?", true, "Specify if edge degree should be detected.")
			.addIntParameter("angelParam", "Angel parameter?", 140, null, "Specify the the threshold angle for edge correction in degrees. The smaller the angle, the more cells will be labeled as edge cells.")
			.addBooleanParameter("keepUncorr", "Keep uncorrelated?", true, "Allow output of uncorrected cluster postions in DBSCAN-CellX output file in addition to corrected cluster positions")
			.addIntParameter("samplingNum", "Sampling Size (0: all, default: 10000)", 10000, "objects(s)", "Number of objects for DBSCAN-CellX analysis. A larger number resuls more time and space cosuming.")
			.addStringParameter("label", "Label:", "dbscan-cellx", "label")
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
				
			final String uuid = UUID.randomUUID().toString().replace("-", "")+hackDigit.getAndIncrement();
			
			final Path dataPath = Files.createTempFile("QuST-dbscancellx_data-" + uuid + "-", ".csv");
            final String dataPathString = dataPath.toAbsolutePath().toString();
//            dataPath.toFile().deleteOnExit();
			
            final Path resultPath = Files.createTempFile("QuST-dbscancellx_result-" + uuid + "-", ".csv");
            final String resultPathString = resultPath.toAbsolutePath().toString();
//            resultPath.toFile().deleteOnExit();
            
			try {
	            /*
	             * Generate cell masks with their labels
	             */
				
		        final int sltdAnnotNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isAnnotation() && e.hasChildObjects()).collect(Collectors.toList()).size();
		        final int sltdDetNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isDetection() && !e.hasChildObjects()).collect(Collectors.toList()).size();
		        
		        final List<PathObject> allDetectionPathObjectList = Collections.synchronizedList(new ArrayList<>());
		        		
		        if(sltdAnnotNum > 0 && sltdDetNum == 0) {
		        	final List<PathObject> selectedAnnotationPathObjectList = 
						hierarchy
						.getSelectionModel()
						.getSelectedObjects()
						.stream()
						.filter(e -> e.isAnnotation() && e.hasChildObjects())
						.collect(Collectors.toList());
		        	
		        	selectedAnnotationPathObjectList.parallelStream().forEach(p -> {
						synchronized(allDetectionPathObjectList) {
							allDetectionPathObjectList.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()));
						}
					});
		        }
		        else if(sltdAnnotNum == 0 && sltdDetNum > 0) {
		        	allDetectionPathObjectList.addAll(
			        	hierarchy
			        	.getSelectionModel()
			        	.getSelectedObjects()
			        	.stream()
			        	.filter(e -> e.isDetection() && !e.hasChildObjects())
			        	.collect(Collectors.toList()));
		        }
		        else if(sltdAnnotNum > 0 && sltdDetNum > 0) {
		        	Dialogs.showErrorMessage("Error", "Do not select both annotations and detections.");
		        	throw new Exception("Do not select both annotations and detections.");
		        }
		        else {
		        	Dialogs.showErrorMessage("Error", "No annotations/detections are selected.");
		        	throw new Exception("No annotations/detections are selected.");
		        }
				
				Collections.shuffle(allDetectionPathObjectList);
		
				if(allDetectionPathObjectList.size() < qustSetup.getNormalizationSampleSize()) throw new Exception("Insufficient sample size.");
				
				final List<PathObject> cellSizeSamplingPathObjects = Collections.synchronizedList(allDetectionPathObjectList.subList(0, qustSetup.getNormalizationSampleSize()));
				final double cellMax = cellSizeSamplingPathObjects.stream().map(d -> d.getMeasurementList().get("Cell: Max diameter µm")).mapToDouble(Double::doubleValue).average().getAsDouble();
				final double cellMin = cellSizeSamplingPathObjects.stream().map(d -> d.getMeasurementList().get("Cell: Min diameter µm")).mapToDouble(Double::doubleValue).average().getAsDouble();
				final double cellSize = 0.5*(cellMax+cellMin);
				
				if(allDetectionPathObjectList.size() < params.getIntParameterValue("samplingNum") && params.getIntParameterValue("samplingNum") != 0) throw new Exception("Insufficient sample size.");
				
				final List<PathObject> samplingPathObjects = params.getIntParameterValue("samplingNum") == 0? allDetectionPathObjectList: allDetectionPathObjectList.subList(0, params.getIntParameterValue("samplingNum"));
				
		        final ImageServer<BufferedImage> server = QP.getCurrentImageData().getServer();
		        final double pixelSize = server.getPixelCalibration().getAveragedPixelSize().doubleValue();
		        final int imageHeight = server.getHeight();
		        final int imageWidth = server.getWidth();
		        final String imageId = server.getURIs().toString();
                
                final CSVWriter writer = new CSVWriter(new FileWriter(dataPathString));

		        //Create record
		        String[] recordBuf = "Image,Cell-ID,X,Y".split(",");

		        //Write the record to file
		        writer.writeNext(recordBuf, false);

		        samplingPathObjects.stream().forEach(d -> {
		        	final String cellId = d.getID().toString();
		        	final double x = pixelSize*d.getROI().getCentroidX();
		        	final double y = pixelSize*d.getROI().getCentroidY();
		        	recordBuf[0] = imageId;
		        	recordBuf[1] = cellId;
		        	recordBuf[2] = Double.toString(x);
		        	recordBuf[3] = Double.toString(y);
		        	
		        	writer.writeNext(recordBuf, false);
		        });
		        
		        //close the writer
		        writer.close();
		        
		        
		        if(dataPath.toFile().exists()) {
		        	logger.error("Validing "+dataPath.toString()+" Ok!\n");
		        }
		        else {
					logger.error("Missing "+dataPath.toString()+"\n");
					throw new Exception("Missing "+dataPath.toString()+"\n");
				}
		        
				// Create command to run
		        VirtualEnvironmentRunner veRunner;
		        veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), DBSCANCellX.class.getSimpleName(), qustSetup.getSptx2ScriptPath());
			
		        // This is the list of commands after the 'python' call
		        List<String> QuSTArguments = new ArrayList<>(Arrays.asList("-W", "ignore", "-m", "dbscan_cellx"));
		        
//		        -f [FILES ...], --files [FILES ...]
//		                              Add path to files in .csv format.
//		        -sa SAVE, --save SAVE
//		                              Specify path to directory where outputf files should
//		                              be saved. Note: path should end with "/" in unix based
//		                              systems or with "\ " in Windows systems.
//		        -p PIXEL_RATIO, --pixel_ratio PIXEL_RATIO
//		                              Plese specify the pixel edge size in microns (micron
//		                              to pixel ratio)
//		        -c CELL_SIZE, --cell_size CELL_SIZE
//		                              Plese specify the estimated cell size in microns
//		        -x SIZE_X, --size_x SIZE_X
//		                              Please enter image size in microns in X direction
//		        -y SIZE_Y, --size_y SIZE_Y
//		                              Please enter image size in microns in Y direction
//		        -e EDGE_MODE, --edge_mode EDGE_MODE
//		                              Specify if edge degree should be detected. Enter 1 if
//		                              edge degree is desired, 0 if it is not.
//		        -a ANGEL_PARAMETER, --angel_parameter ANGEL_PARAMETER
//		                              Please specify the the threshold angle for edge
//		                              correction in degrees. The smaller the angle, the more
//		                              cells will be labeled as edge cells.
//		        -sp SAVE_PARAMETER, --save_parameter SAVE_PARAMETER
//		                              Allow seperate output of calculated Epsilon and n_min.
//		                              Enter 1 if output is desired, 0 if it is not.
//		        -ku KEEP_UNCORRECTED, --keep_uncorrected KEEP_UNCORRECTED
//		                              Allow output of uncorrected cluster postions in
//		                              DBSCAN-CellX output file in addition to corrected
//		                              cluster positions. Enter 1 if output is desired, 0 if
//		                              it is not.
		                              
		        QuSTArguments.add("-f");
		        QuSTArguments.add(dataPathString);
		        veRunner.setArguments(QuSTArguments);
		        
		        QuSTArguments.add("-sa");
		        QuSTArguments.add(resultPathString);
		        veRunner.setArguments(QuSTArguments);

		        QuSTArguments.add("-p");
		        QuSTArguments.add(Double.toString(pixelSize));
		        veRunner.setArguments(QuSTArguments);
		        
		        QuSTArguments.add("-c");
		        QuSTArguments.add(Double.toString(cellSize));
		        veRunner.setArguments(QuSTArguments);
		        
		        QuSTArguments.add("-x");
		        QuSTArguments.add(Double.toString((double)imageWidth*pixelSize));
		        veRunner.setArguments(QuSTArguments);
		        
		        QuSTArguments.add("-y");
		        QuSTArguments.add(Double.toString((double)imageHeight*pixelSize));
		        veRunner.setArguments(QuSTArguments);
		        
		        QuSTArguments.add("-e");
		        QuSTArguments.add(params.getBooleanParameterValue("edgeMode")? "1": "0");
		        veRunner.setArguments(QuSTArguments);
		        
		        QuSTArguments.add("-a");
		        QuSTArguments.add(params.getIntParameterValue("angelParam").toString());
		        veRunner.setArguments(QuSTArguments);
		        
		        QuSTArguments.add("-sp");
		        QuSTArguments.add("0");
		        veRunner.setArguments(QuSTArguments);
		        
		        QuSTArguments.add("-ku");
		        QuSTArguments.add(params.getBooleanParameterValue("keepUncorr")? "1": "0");
		        veRunner.setArguments(QuSTArguments);		        
		        
		        // Finally, we can run the command
		        final String[] logs = veRunner.runCommand();
		        for (String log : logs) logger.info(log);
				
		        if(!new File(resultPathString+"_DBSCAN_CELLX_output.csv").exists()) { 
		            throw new Exception(String.join("\n", Arrays.asList(logs)));
		        }
		        
		        final FileReader resultFileReader = new FileReader(resultPathString+"_DBSCAN_CELLX_output.csv");
		        final CSVParser parser = new CSVParserBuilder().withSeparator(';').build();
		        final CSVReader resultCSVReader = new CSVReaderBuilder(resultFileReader).withCSVParser(parser).build();
		        
		        final String[] resultHead = resultCSVReader.readNext(); 
		        String[] nextRecord; 

		        // we are going to read data line by line 
		        while ((nextRecord = resultCSVReader.readNext()) != null) { 
//		        	nextRecord = nextRecord[0].split(";");
//		        	final String ImageNumber = nextRecord[0];
		        	final String Cell_ID = nextRecord[1];
//		        	final String X = nextRecord[2];
//		        	final String Y = nextRecord[3];

		        	String Uncorrected_Cluster_Position = null;
		        	String Cluster_ID = null;
		        	String Cells_in_Image = null;
		        	String Cells_in_Cluster = null;
		        	String Cluster_Position = null;
		        	String Edge_Degree = null;
		        	
		        	for(int i = 4; i < resultHead.length; i ++) {
		        		if(resultHead[i].equals("Uncorrected_Cluster_Position")) {
		        			Uncorrected_Cluster_Position = nextRecord[i];
		        		}
		        		else if(resultHead[i].equals("Cluster_ID")) {
		        			Cluster_ID = nextRecord[i];
		        		} 
		        		else if(resultHead[i].equals("Cells_in_Image")) {
		        			Cells_in_Image = nextRecord[i];
		        		}
		        		else if(resultHead[i].equals("Cells_in_Cluster")) {
		        			Cells_in_Cluster = nextRecord[i];
		        		}
		        		else if(resultHead[i].equals("Cluster_Position")) {
		        			Cluster_Position = nextRecord[i];
		        		}
		        		else if(resultHead[i].equals("Edge_Degree")) {
		        			Edge_Degree = nextRecord[i];
		        		}
		        	}
		        	
		        	final List<PathObject> resultList = allDetectionPathObjectList
			        		.parallelStream()
	                        .filter(d -> d.getID().toString().equals(Cell_ID))
	                        .collect(Collectors.toList());
		        	
		        	if(resultList.size() == 1) {
		        		final MeasurementList m = resultList.get(0).getMeasurementList();
		        		
		        		final String label = params.getStringParameterValue("label").isBlank()? "": params.getStringParameterValue("label").strip()+":";
		        		if(Uncorrected_Cluster_Position != null) m.put(label+"Uncorrected_Cluster_Position", Uncorrected_Cluster_Position.equals("center")? 0: Uncorrected_Cluster_Position.equals("edge")? 1: 2);
		        		if(Cluster_ID != null) m.put(label+"Cluster_ID", Integer.parseInt(Cluster_ID));
		        		if(Cells_in_Image != null) m.put(label+"Cells_in_Image", Double.parseDouble(Cells_in_Image));
		        		if(Cells_in_Cluster != null) m.put(label+"Cells_in_Cluster", Integer.parseInt(Cells_in_Cluster));
		        		if(Cluster_Position != null) m.put(label+"Cluster_Position", Cluster_Position.equals("center")? 0: Cluster_Position.equals("edge")? 1: 2);
		        		if(Edge_Degree != null) m.put(label+"Edge_Degree", Integer.parseInt(Edge_Degree));
		        	}
		        } 
		        
		        resultCSVReader.close();
		        
		        
//		        hierarchy.getSelectionModel().setSelectedObject(null);
			}
			catch(Exception e) {
				Dialogs.showErrorMessage("Error", e.getMessage());
				lastResults = e.getMessage();
				logger.error(lastResults);
			}	
			finally {
				if(new File(dataPath.toString()).exists()) {
					logger.info("Delete "+dataPath.toString()+"\n");
					dataPath.toFile().delete();
				}
				if(new File(resultPath.toString()).exists()) {
					logger.info("Delete "+resultPath.toString()+"\n");
					resultPath.toFile().delete();
				}
				if(new File(resultPathString+"_DBSCAN_CELLX_output.csv").exists()) {
					logger.info("Delete "+resultPathString+"_DBSCAN_CELLX_output.csv"+"\n");
					new File(resultPathString+"_DBSCAN_CELLX_output.csv").delete();
				}
			}
			
			if (Thread.currentThread().isInterrupted()) {
				Dialogs.showErrorMessage("Warning", "Interrupted!");
				lastResults =  "Interrupted!";
				logger.warn(lastResults);
			}

			return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
		}
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
	}

	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "DBSCAN-CellX";
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
//		list.add(TMACoreObject.class);
		list.add(PathRootObject.class);
		return list;		

//		return Arrays.asList(
//				PathAnnotationObject.class,
//				TMACoreObject.class
//				);
	}
}
