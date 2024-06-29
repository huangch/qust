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
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.StringProperty;
import javafx.geometry.Point2D;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.interfaces.ROI;

/**
 * Plugin for loading AI-DIA annotations 
 * 
 * @author Chao Hui Huang
 *
 */
public class AiDiaAnnotation extends AbstractDetectionPlugin<BufferedImage> {
	
	final private static Logger logger = LoggerFactory.getLogger(AiDiaAnnotation.class);
	
	final private StringProperty aidiaDataFileProp = PathPrefs.createPersistentPreference("aidiaDataFile", ""); 
	
	private ParameterList params;

	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public AiDiaAnnotation() {		
		params = new ParameterList()
				.addTitleParameter("AI-DIA Data Loader")
				.addStringParameter("aidiaDataFile", "AI-DIA Data File", aidiaDataFileProp.get(), "AI-DIA data file")
				.addEmptyParameter("")
				.addBooleanParameter("removeUnlabeledCells", "Remove unlabeled cells? (default: true)", true, "Remove unlabeled cells? (default: true)")		
				.addBooleanParameter("replaceCellId", "Replace Cell Object Ids? (default: true)", true, "Replace Cell Object Ids? (default: true)")		
				.addEmptyParameter("")
				.addIntParameter("maskDownsampling", "Downsampling for transcript to cell assignment", 2, null, "Downsampling for cell-transciptome assignment")			
				;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		/* --------------------------------------------------------------------*/
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
//			aidiaDataFileProp.set(params.getStringParameterValue("aidiaDataFile"));
			
			final ImageServer<BufferedImage> server = imageData.getServer();				
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
			final ArrayList<PathObject> resultPathObjectList = new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			
			try {
				final double pixelSizeMicrons = server.getPixelCalibration().getAveragedPixelSizeMicrons();
				/*
	             * Generate cell masks with their labels
	             */
				
				final List<PathObject> selectedAnnotationPathObjectList = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isAnnotation() && e.hasChildObjects()).collect(Collectors.toList());
				
				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
				final int maskDownsampling = params.getIntParameterValue("maskDownsampling");;
				final int maskWidth = (int)Math.round(server.getWidth()/maskDownsampling);
				final int maskHeight = (int)Math.round(server.getHeight()/maskDownsampling);	
				final BufferedImage annotPathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
				final List<PathObject> annotPathObjectList = new ArrayList<PathObject>();						
				
				final Graphics2D annotPathObjectG2D = annotPathObjectImageMask.createGraphics();				
				annotPathObjectG2D.setBackground(new Color(0, 0, 0));
				annotPathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
				
				annotPathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
				annotPathObjectG2D.scale(1.0/maskDownsampling, 1.0/maskDownsampling);					    
				
				final BufferedImage pathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
				final List<PathObject> pathObjectList = new ArrayList<PathObject>();						
				
				final Graphics2D pathObjectG2D = pathObjectImageMask.createGraphics();				
				pathObjectG2D.setBackground(new Color(0, 0, 0));
				pathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
				
				pathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
				pathObjectG2D.scale(1.0/maskDownsampling, 1.0/maskDownsampling);
				
				try {
					int annotPathObjectCount = 1;
					int pathObjectCount = 1;
					
					for(PathObject p: selectedAnnotationPathObjectList) {
						annotPathObjectList.add(p);
					    
					    final int pb0 = (annotPathObjectCount & 0xff) >> 0; // b
					    final int pb1 = (annotPathObjectCount & 0xff00) >> 8; // g
					    final int pb2 = (annotPathObjectCount & 0xff0000) >> 16; // r
					    final Color pMaskColor = new Color(pb2, pb1, pb0); // r, g, b
				    
					    final ROI pRoi = p.getROI();
						final Shape pShape = pRoi.getShape();
						
						annotPathObjectG2D.setColor(pMaskColor);
						annotPathObjectG2D.fill(pShape);
						
						annotPathObjectCount ++;
					    if(annotPathObjectCount == 0xffffff) {
					    	throw new Exception("annotation count overflow!");
					    }
						
						for(PathObject c: p.getChildObjects()) {
							pathObjectList.add(c);
						    
						    final int b0 = (pathObjectCount & 0xff) >> 0; // b
						    final int b1 = (pathObjectCount & 0xff00) >> 8; // g
						    final int b2 = (pathObjectCount & 0xff0000) >> 16; // r
						    final Color maskColor = new Color(b2, b1, b0); // r, g, b
					    
						    final ROI roi = c.getROI();
							final Shape shape = roi.getShape();
							
							pathObjectG2D.setColor(maskColor);
							pathObjectG2D.fill(shape);
							
							pathObjectCount ++;
						    if(pathObjectCount == 0xffffff) {
						    	throw new Exception("Cell count overflow!");
						    }
						}
					}	
				}
				catch(Exception e) {
					throw e;
				}
				finally {
					annotPathObjectG2D.dispose();	
					pathObjectG2D.dispose();	
				}
				
	            /*
	             * Read single cell data
	             * "Image   Object ID       Name    Class   Parent  ROI     Centroid X um   Centroid Y um   Area um^2       Perimeter um
	             */

				if(aidiaDataFileProp.get().isBlank()) throw new Exception("AI-DIA Data File is blank");
				
				final HashMap<String, PathObject> cellToPathObjHashMap = new HashMap<>();
			
				final String singleCellFilePath = java.nio.file.Paths.get(aidiaDataFileProp.get()).toString();
				final FileReader singleCellFileReader = new FileReader(new File(singleCellFilePath));
				final BufferedReader singleCellReader = new BufferedReader(singleCellFileReader);

				singleCellReader.readLine();
				String singleCellNextRecord;
				
		        while ((singleCellNextRecord = singleCellReader.readLine()) != null) {
		        	final String[] singleCellNextRecordArray = singleCellNextRecord.split("\t");
		        	final String cellId = singleCellNextRecordArray[1]; // .replaceAll("\"", "");
		        	
		        	final double cx = Double.parseDouble(singleCellNextRecordArray[7])/pixelSizeMicrons;
		        	final double cy = Double.parseDouble(singleCellNextRecordArray[8])/pixelSizeMicrons;
		        			        	
		        	final int fx = (int)Math.round(cx / maskDownsampling);
		        	final int fy = (int)Math.round(cy / maskDownsampling);
		        	
		        	if(fx < 0 || fx >= pathObjectImageMask.getWidth() || fy < 0 || fy >=  pathObjectImageMask.getHeight()) continue;
		        	
		        	final int v = pathObjectImageMask.getRGB(fx, fy);
		        	final int d0 = v&0xff;
		        	final int d1 = (v>>8)&0xff;
		        	final int d2 = (v>>16)&0xff;
					final int r = d2*0x10000+d1*0x100+d0;
				    
		        	if(r == 0) continue; // This location doesn't have a cell.
			        	
		        	final int pathObjectId = r - 1;  // pathObjectId starts at 1, since 0 means background
			        	
		        	final PathObject cellPathObject = pathObjectList.get(pathObjectId);
		        	cellToPathObjHashMap.put(cellId, cellPathObject);
	
		        	final String scLabelId = singleCellNextRecordArray[4];
		        	if(scLabelId != null) {        		
		        		final PathClass pathCls = PathClass.fromString(scLabelId);
		        		cellPathObject.setPathClass(pathCls);
		        	}
		        	
		        	final double roiX = cellPathObject.getROI().getCentroidX();
		        	final double roiY = cellPathObject.getROI().getCentroidY();
		        	final double newDist = (new Point2D(cx, cy).distance(roiX, roiY))*pixelSizeMicrons;
		        	final MeasurementList pathObjMeasList = cellPathObject.getMeasurementList();
		        	
		        	if(pathObjMeasList.containsKey("aidia:pred:displacement")) {
		        		final double minDist = pathObjMeasList.get("aidia:pred:displacement");
		        		if(newDist < minDist) {
		        			if(params.getBooleanParameterValue("replaceCellId")) cellPathObject.setName(cellId);
		        			pathObjMeasList.put("aidia:pred:displacement", newDist);
		        			pathObjMeasList.put("aidia:pred:x_centroid", cx);
		        			pathObjMeasList.put("aidia:pred:y_centroid", cy);
		        		}
		        	}
		        	else {
		        		if(params.getBooleanParameterValue("replaceCellId")) cellPathObject.setName(cellId);
	        			pathObjMeasList.put("aidia:pred:displacement", newDist);
	        			pathObjMeasList.put("aidia:pred:x_centroid", cx);
	        			pathObjMeasList.put("aidia:pred:y_centroid", cy);
	        		}
		        	
		        	pathObjMeasList.put("aidia:pred:"+singleCellNextRecordArray[4], 1);
		        	
		        	pathObjMeasList.close(); 
	        	}		        	
	        	
		        singleCellReader.close();
				
				if(params.getBooleanParameterValue("removeUnlabeledCells")) {
					for(PathObject c: pathObjectList) {
						if(c.getPathClass() == null) {
							c.getParent().removeChildObject(c);
						}
					}
				}

		        hierarchy.getSelectionModel().setSelectedObject(null);
			}
			catch(Exception e) {	
				Dialogs.showErrorMessage("Error", e.getMessage());
				
				lastResults =  "Something went wrong: "+e.getMessage();
				logger.error(lastResults);
				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			}				
			
			if (Thread.currentThread().isInterrupted()) {
				Dialogs.showErrorMessage("Warning", "Interrupted!");
				lastResults =  "Interrupted!";
				logger.warn(lastResults);
				
				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			}
			
			return resultPathObjectList;
		}
		
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
		
		
	}

	
	@Override
	protected void preprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		if(params.getStringParameterValue("aidiaDataFile").isBlank()) {
			final File aidiaFileFp = Dialogs.promptForFile("AI-DIA Data File", new File(aidiaDataFileProp.get()), "QuPath detection result file (*.txt)", ".txt");
			
			if (aidiaFileFp != null) {
				aidiaDataFileProp.set(aidiaFileFp.toString());
			}
			else {
				Dialogs.showErrorMessage("Warning", "AI-DIA Data File is not selected!");
				lastResults =  "AI-DIA Data File is not selected!";
				logger.warn(lastResults);
			}
		}
		else {
			aidiaDataFileProp.set(params.getStringParameterValue("aidiaDataFile"));
		}
	};

	
	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
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

		return Arrays.asList(
				PathAnnotationObject.class,
				TMACoreObject.class
				);		
	}

}
