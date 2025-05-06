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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.objects.PathDetectionObject;
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
import qupath.lib.roi.interfaces.ROI.RoiType;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.common.GeneralTools;
import qupath.lib.geom.Point2;
import qupath.lib.images.ImageData;

/**
 * Plugin for Exporting DetectionObjects To OMECSV file
 * 
 * @author Chao Hui Huang
 *
 */
public class DetectionObjectToOMECSV extends AbstractDetectionPlugin<BufferedImage> {
	
	private static Logger logger = LoggerFactory.getLogger(DetectionObjectToOMECSV.class);
	private static ParameterList params = null;
	private static File outFile = null;
	private static Collection<PathObject> chosen_objects = new ArrayList<PathObject>();
	private static String allObjects = "All objects";
	private static String selectedObjects = "Selected objects";
	private static String lastResults = null;
	
	/**
	 * Constructor.
	 * @throws Exception 
	 */
	public DetectionObjectToOMECSV() throws Exception {
		// Get ImageData
		QuPathGUI qupath = QuPathGUI.getInstance();
		ImageData<BufferedImage> imageData = qupath.getImageData();
		
		if (imageData == null)
			return;
		
		// Get hierarchy
		PathObjectHierarchy hierarchy = imageData.getHierarchy();
	
		String defaultObjects = hierarchy.getSelectionModel().noSelection() ? allObjects : selectedObjects;
		
        params = new ParameterList()
        	.addStringParameter("fileName", "Save to ", "", "Save detection measurement data to the chosen h5ad file")
        	.addChoiceParameter("exportOptions", "Export ", defaultObjects, Arrays.asList(allObjects, selectedObjects), "Choose which objects to export - run a 'Select annotations/detections' command first if needed")
			.addChoiceParameter("exportType", "Type", "annotation", Arrays.asList("annotation", "detection"), "Type")				
			.addBooleanParameter("excludeMeasurements", "Exclude measurements", false, "Exclude object measurements during export - for large numbers of detections this can help reduce the file size")
			.addIntParameter("downsampling", "Downsampling factor (default: 2)", 2, null, "Downsampling factor for reducing OMERO loading")
			.addStringParameter("lblPrefix", "Label prefix", "", "Label prefix")
			;
	}
	
	
	/**
	 * Run the path detection object measurement H5AD export command.
	 * @param outFile 
	 * @return success
	 * @throws IOException 
	 */
	/**
	 * Run the path object OME-CSV export command.
	 * @param outFile 
	 * @return success
	 * @throws IOException 
	 */
	private static void writeCSV(String outFile, Collection<PathObject> chosenObjects, String type, ImageData<?> imageData, int downsampling, boolean excludeMeasurements, String lblPrefix) throws IOException {
		final List<PathObject> pathObjectList = new ArrayList<PathObject>(chosenObjects);
		
		final PathObjectHierarchy hierarchy = imageData.getHierarchy();
		final ObservableMeasurementTableData measTblData = new ObservableMeasurementTableData();
		measTblData.setImageData(imageData, imageData == null ? Collections.emptyList() : hierarchy.getObjects(null, PathDetectionObject.class));
		final List<String> measNames = measTblData.getMeasurementNames();
		
		final List<List<String>> rows = Collections.synchronizedList(new ArrayList<>());
		
		final List<String> header = new ArrayList<String>(Arrays.asList("object", "secondary_object", "polygon", "objectType", "classification"));
		if(!excludeMeasurements) header.addAll(measNames);
		rows.add(header);
		
		IntStream.range(0, chosenObjects.size()).parallel().forEach(i -> {
//		for(int i = 0; i < chosenObjects.size(); i ++) {
			final PathObject obj = pathObjectList.get(i);
			
			try {
				// Extract ROI & classification name
			    final ROI roi = obj.getROI();
				
			    final List<String> row = new ArrayList<>();
			    		
			    
			    if(roi.getRoiType() == RoiType.POINT) {
			    	final List<Point2> pntList = roi.getAllPoints();
				    
			    	final List<String> coordList = pntList.stream().map(p -> String.format("%d %d",(int)(p.getX()), (int)(p.getY()))).collect(Collectors.toList());		
			    	final String coordListStr = String.format("\"POINT (%s)\"", String.join(", ", coordList));
			   
			    	if(obj != null && obj.getPathClass() != null) {
						row.add(String.valueOf(i+1));
						row.add(String.valueOf(i+1));
						row.add(coordListStr); 
						row.add(type);
						row.add(lblPrefix+obj.getPathClass().toString());
						
						if(!excludeMeasurements) {
							final List<String> valueList = measNames.stream().map(m -> String.valueOf(obj.getMeasurementList().get(m))).collect(Collectors.toList());
							row.addAll(valueList);
						}
						
						synchronized (rows) {
							rows.add(row);
						}					
			    	}
			    }
			    else if(roi.getRoiType() == RoiType.AREA) {
				    // Create a region from the ROI
	
				    final RegionRequest region = RegionRequest.createInstance(imageData.getServer().getPath(), downsampling, roi);
				    
				    // Create a mask using Java2D functionality
				    // (This involves applying a transform to a graphics object, so that none needs to be applied to the ROI coordinates)
				    final Shape shape = roi.getShape();
				    final BufferedImage imgMask = new BufferedImage(region.getWidth(), region.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
				    Graphics2D g2d = imgMask.createGraphics();
				    g2d.setColor(Color.WHITE);
				    g2d.scale(1.0/downsampling, 1.0/downsampling);
				    g2d.translate(-region.getX(), -region.getY());
				    g2d.fill(shape);
				    g2d.dispose();
		
				    final ROI tracedRoi = ContourTracing.createTracedROI(imgMask.getData(), 255, 255, 0, region);
				    final List<Point2> pntList = tracedRoi.getAllPoints();
				    
				    if (pntList.size() > 3 && obj != null && obj.getPathClass() != null) {
				    	pntList.add(new Point2(pntList.get(0).getX(), pntList.get(0).getY()));
				    	final List<String> coordList = pntList.stream().map(p -> String.format("%d %d",(int)(p.getX()), (int)(p.getY()))).collect(Collectors.toList());		
				    	final String coordListStr = String.format("\"POLYGON ((%s))\"", String.join(", ", coordList));
				    	row.add(String.valueOf(i+1));
						row.add(String.valueOf(i+1));
						row.add(coordListStr); 
						row.add(type);
						row.add(lblPrefix+obj.getPathClass().toString());	
						
						if(!excludeMeasurements) {
							final List<String> valueList = measNames.stream().map(m -> String.valueOf(obj.getMeasurementList().get(m))).collect(Collectors.toList());
							row.addAll(valueList);
						}
						
						synchronized (rows) {
							rows.add(row);
						}
				    }
			    }
		    }		
		    catch (Exception e) {
		    	logger.error("Exception occurred: "+obj.toString());
		    }

		});
//		}
		
		try (var gzos = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)))) {
			for(final List<String> r: rows) {
				final byte[] input = (String.join(",", r)+"\n").getBytes("UTF-8");
	    		gzos.write(input,0, input.length);
			}
	      
	      	gzos.close();
	 	}
	}
	
	@Override
	protected void preprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		String fileName = params.getStringParameterValue("fileName");
		
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
			
			outFile = FileChoosers.promptToSaveFile("Export to file", defaultFile,
					FileChoosers.createExtensionFilter("Gzipped OMERO-compatible Comma-Separated Values (.OGZ)", ".ogz"));
		}
		else {
			outFile = new File(fileName);
		}
		
	}
	
	@Override
	protected void postprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		// Export
		try {
			if(chosen_objects.size() > 0) {
				final int downsampling = params.getIntParameterValue("downsampling");
				final boolean excludeMeasurements = params.getBooleanParameterValue("excludeMeasurements");
				final String lblPrefix = params.getStringParameterValue("lblPrefix").strip();
				
				// If user cancels
				if (outFile == null)
					return;
				else if (outFile.exists()) 
					outFile.delete();
				
				writeCSV(outFile.getAbsolutePath(), chosen_objects, params.getChoiceParameterValue("exportType").toString(), imageData, downsampling, excludeMeasurements, lblPrefix);
			}
		} catch (IOException e1) {
			e1.printStackTrace();
		}
	}
	
	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "LLM";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}

	@Override
	public String getDescription() {
		return "Detect one or more regions of interest by applying a global threshold";
	}

	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			// Get hierarchy
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			
			Collection<PathObject> toProcess;
			
			String comboChoice = (String)params.getChoiceParameterValue("exportOptions");
			if (comboChoice.equals(selectedObjects)) {
				if (hierarchy.getSelectionModel().noSelection()) {
					Dialogs.showErrorMessage("No selection", "No selection detected!");
					return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());

				}
				toProcess = hierarchy.getSelectionModel().getSelectedObjects();
			} else
				toProcess = hierarchy.getObjects(null, null);
			
			
			// Remove PathRootObject
			toProcess = toProcess.stream().filter(e -> !e.isRootObject()).toList();

			toProcess.stream().forEach(p -> {
				if (p.isAnnotation() && p.hasChildObjects()) {
					synchronized (chosen_objects) {
						chosen_objects.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()));
					}
				}	
				else if(p.isDetection()) {
					synchronized(chosen_objects) {
						chosen_objects.add(p);
					}
				}	
			});
			
			if (Thread.currentThread().isInterrupted()) {
				Dialogs.showErrorMessage("Warning", "Interrupted!");
				lastResults =  "Interrupted!";
				logger.warn(lastResults);
			}
			
			return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
//			return hierarchy.getRootObject().getChildObjects();
		}
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
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
		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(TMACoreObject.class);
		list.add(PathRootObject.class);
		return list;		

//		return Arrays.asList(
//				PathAnnotationObject.class,
//				TMACoreObject.class
//				);
	}
}
