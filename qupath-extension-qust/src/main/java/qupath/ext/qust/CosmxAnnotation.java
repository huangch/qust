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
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;


import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;

import javafx.beans.property.StringProperty;
import javafx.geometry.Point2D;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.interfaces.ROI;

import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * Plugin for loading CosMX Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class CosmxAnnotation extends AbstractDetectionPlugin<BufferedImage> {
	
	private static Logger logger = LoggerFactory.getLogger(CosmxAnnotation.class);
	private StringProperty cosmxAntnCosmxFldrProp = PathPrefs.createPersistentPreference("cosmxAntnCosmxFldr", ""); 
	private ParameterList params;
	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public CosmxAnnotation() {
		params = new ParameterList()
			.addTitleParameter("NanoString Cosmx Data Loader")
			.addStringParameter("cosmxDir", "Cosmx directory", cosmxAntnCosmxFldrProp.get(), "Cosmx Out Directory")
			.addBooleanParameter("consolToAnnot", "Consolidate transcript data to Visium-style spots? (default: false)", false, "Consolidate Transcript Data to Annotations? (default: false)")
			.addBooleanParameter("removeUnlabeledCells", "Remove unlabeled cells? (default: true)", true, "Remove unlabeled cells? (default: true)")		
			.addEmptyParameter("")
			.addBooleanParameter("inclGeneExpr", "Include Gene Expression? (default: true)", true, "Include Gene Expression? (default: true)")		
			.addBooleanParameter("inclNegCtrlProbe", "Include Negative Control Probe? (default: false)", false, "Include Negative Control Probe? (default: false)")		
			.addEmptyParameter("")

			.addIntParameter("maskDownsampling", "Downsampling for transcript to cell assignment", 2, null, "Downsampling for cell-transciptome assignment")			
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			cosmxAntnCosmxFldrProp.set(params.getStringParameterValue("cosmxDir"));
			
//			ImageServer<BufferedImage> server = imageData.getServer();				
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			ArrayList<PathObject> resultPathObjectList = new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			
			try {
				
            
		        // double pixelSizeMicrons = server.getPixelCalibration().getAveragedPixelSizeMicrons();
		        
	            /*
	             * Generate cell masks with their labels
	             */
				
				List<PathObject> selectedAnnotationPathObjectList = new ArrayList<>();
				
				for (PathObject pathObject : hierarchy.getSelectionModel().getSelectedObjects()) {
					if (pathObject.isAnnotation() && pathObject.hasChildObjects())
						selectedAnnotationPathObjectList.add(pathObject);
				}	
				
				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");

				int maskDownsampling = params.getIntParameterValue("maskDownsampling");;
				int maskWidth = (int)Math.round(imageData.getServer().getWidth()/maskDownsampling);
				int maskHeight = (int)Math.round(imageData.getServer().getHeight()/maskDownsampling);	
				
				BufferedImage annotPathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
				List<PathObject> annotPathObjectList = new ArrayList<PathObject>();						
				
				Graphics2D annotPathObjectG2D = annotPathObjectImageMask.createGraphics();				
				annotPathObjectG2D.setBackground(new Color(0, 0, 0));
				annotPathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
				
				annotPathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
				annotPathObjectG2D.scale(1.0/maskDownsampling, 1.0/maskDownsampling);					    
				
				BufferedImage pathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
				List<PathObject> pathObjectList = new ArrayList<PathObject>();						
				
				Graphics2D pathObjectG2D = pathObjectImageMask.createGraphics();				
				pathObjectG2D.setBackground(new Color(0, 0, 0));
				pathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
				
				pathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
				pathObjectG2D.scale(1.0/maskDownsampling, 1.0/maskDownsampling);
				
				try {
					int annotPathObjectCount = 1;
					int pathObjectCount = 1;
					
					for(PathObject p: selectedAnnotationPathObjectList) {
						annotPathObjectList.add(p);
					    
					    int pb0 = (annotPathObjectCount & 0xff) >> 0; // b
					    int pb1 = (annotPathObjectCount & 0xff00) >> 8; // g
					    int pb2 = (annotPathObjectCount & 0xff0000) >> 16; // r
					    Color pMaskColor = new Color(pb2, pb1, pb0); // r, g, b
				    
					    ROI pRoi = p.getROI();
						Shape pShape = pRoi.getShape();
						
						annotPathObjectG2D.setColor(pMaskColor);
						annotPathObjectG2D.fill(pShape);
						
						annotPathObjectCount ++;
						
					    if(annotPathObjectCount == 0xffffff) {
					    	throw new Exception("annotation count overflow!");
					    }
						
						for(PathObject c: p.getChildObjects()) {
							pathObjectList.add(c);
						    
						    int b0 = (pathObjectCount & 0xff) >> 0; // b
						    int b1 = (pathObjectCount & 0xff00) >> 8; // g
						    int b2 = (pathObjectCount & 0xff0000) >> 16; // r
						    Color maskColor = new Color(b2, b1, b0); // r, g, b
					    
						    ROI roi = c.getROI();
							Shape shape = roi.getShape();
							
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
	             * "cell_id","x_centroid","y_centroid","transcript_counts","control_probe_counts","control_codeword_counts","total_counts","cell_area","nucleus_area"
	             */
				
				
				if(cosmxAntnCosmxFldrProp.get().isBlank()) throw new Exception("singleCellFile is blank");
				
				HashMap<String, PathObject> cellToPathObjHashMap = new HashMap<>();
				File cosmxDir = new File(cosmxAntnCosmxFldrProp.get());
				FileFilter cosmxFovPosFileFilter = new WildcardFileFilter("*fov_positions_file.csv");
				File[] cosmxFovPosFileList = cosmxDir.listFiles(cosmxFovPosFileFilter);
				if(cosmxFovPosFileList.length != 1) throw new Exception("*fov_positions_file.csv error");
				
				FileReader fovPosFileReader = new FileReader(new File(cosmxFovPosFileList[0].toString()));
				BufferedReader fovPosBufferedReader = new BufferedReader(fovPosFileReader);
				fovPosBufferedReader.readLine();
				String fovPosNextRecord;
				
				double x_global_min = -1.0;
				double y_global_min = -1.0;
				
		        while ((fovPosNextRecord = fovPosBufferedReader.readLine()) != null) {
		        	String[] fovPosNextRecordArray = fovPosNextRecord.split(",");
		        	
		        	double x_global_px = (double)(Double.parseDouble(fovPosNextRecordArray[1]));
		        	double y_global_px = (double)(Double.parseDouble(fovPosNextRecordArray[2]));
		        	
		        	if(x_global_min == -1.0 || x_global_px < x_global_min) x_global_min = x_global_px;
		        	if(y_global_min == -1.0 || y_global_px < y_global_min) y_global_min = y_global_px;
		        }
				
		        fovPosBufferedReader.close();
				
				FileFilter cosmxMetadataFileFilter = new WildcardFileFilter("*metadata_file.csv");
				File[] cosmxMetadataFileList = cosmxDir.listFiles(cosmxMetadataFileFilter);
				if(cosmxMetadataFileList.length != 1) throw new Exception("*metadata_file.csv error");
				
				FileReader singleCellFileReader = new FileReader(new File(cosmxMetadataFileList[0].toString()));
				BufferedReader singleCellBufferedReader = new BufferedReader(singleCellFileReader);
				singleCellBufferedReader.readLine();
				String singleCellNextRecord;
				
		        while ((singleCellNextRecord = singleCellBufferedReader.readLine()) != null) {
				        
		        	String[] singleCellNextRecordArray = singleCellNextRecord.split(",");
		        	
		        	int fov = Integer.parseInt(singleCellNextRecordArray[0].replaceAll("\"", ""));
		        	int cellId = Integer.parseInt(singleCellNextRecordArray[1].replaceAll("\"", ""));
		        	
		        	double cx = Double.parseDouble(singleCellNextRecordArray[6]);
		        	double cy = Double.parseDouble(singleCellNextRecordArray[7]);
		        	
		        	double dx = cx-x_global_min;
		        	double dy = cy-y_global_min;
		        	
		        	int fX = (int)(0.5+dx/maskDownsampling);
		        	int fY = (int)(0.5+dy/maskDownsampling);
		        	
		        	if(fX < 0 || fX >= pathObjectImageMask.getWidth() || fY < 0 || fY >= pathObjectImageMask.getHeight()) continue;
		        	
		        	int v = pathObjectImageMask.getRGB(fX, fY);
		        	int d0 = v&0xff;
		        	int d1 = (v>>8)&0xff;
		        	int d2 = (v>>16)&0xff;
					int r = d2*0x10000+d1*0x100+d0;
				    
		        	if(r == 0) continue; // This location doesn't have a cell.
			        	
		        	int pathObjectId = r - 1;  // pathObjectId starts at 1, since 0 means background
			        	
		        	PathObject cellPathObject = pathObjectList.get(pathObjectId);
		        	cellToPathObjHashMap.put(String.valueOf(cellId)+"_"+String.valueOf(fov), cellPathObject);
		        	
		        	double roiX = cellPathObject.getROI().getCentroidX();
		        	double roiY = cellPathObject.getROI().getCentroidY();
		        	// double newDist = (new Point2D(dx, dy).distance(roiX, roiY))*pixelSizeMicrons;
		        	double newDist = (new Point2D(dx, dy).distance(roiX, roiY));
		        	MeasurementList pathObjMeasList = cellPathObject.getMeasurementList();
		        	if(pathObjMeasList.containsKey("cosmx:cell:cell_id")) {
		        		double minDist = pathObjMeasList.get("cosmx:cell:displacement");
		        		if(newDist < minDist) {
		        			pathObjMeasList.put("cosmx:cell:fov", fov);
		        			pathObjMeasList.put("cosmx:cell:cell_id", cellId);
		        			pathObjMeasList.put("cosmx:cell:displacement", newDist);
		        			pathObjMeasList.put("cosmx:cell:x_centroid", (double)dx);
		        			pathObjMeasList.put("cosmx:cell:y_centroid", (double)dy);
		        		}
		        	}
		        	else {
		        		pathObjMeasList.put("cosmx:cell:fov", fov);
		        		pathObjMeasList.put("cosmx:cell:cell_id", cellId);
	        			pathObjMeasList.put("cosmx:cell:displacement", newDist);
	        			pathObjMeasList.put("cosmx:cell:x_centroid", (double)dx);
	        			pathObjMeasList.put("cosmx:cell:y_centroid", (double)dy);
		        	}
		        	
		        	pathObjMeasList.close(); 
	        	}		        	
		        	
		        singleCellBufferedReader.close();
				
				/*
	             * Read feature matrix data
	             */
			        
		        FileFilter cosmxExprMatFileFilter = new WildcardFileFilter("*exprMat_file.csv");
				File[] cosmxExprMatFileList = cosmxDir.listFiles(cosmxExprMatFileFilter);
				if(cosmxExprMatFileList.length != 1) throw new Exception("*exprMat_file.csv");
				
				FileReader exprMatFileReader = new FileReader(new File(cosmxExprMatFileList[0].toString()));
				BufferedReader exprMatBufferedReader = new BufferedReader(exprMatFileReader);
				String[] exprMatHeaders = exprMatBufferedReader.readLine().split(",");
				
				String exprMatNextRecord;
		        while ((exprMatNextRecord = exprMatBufferedReader.readLine()) != null) {
		        	String[] exprMatNextRecordArray = exprMatNextRecord.split(",");
		        	
		        	int fov = Integer.parseInt(exprMatNextRecordArray[0].replaceAll("\"", ""));
		        	int cellId = Integer.parseInt(exprMatNextRecordArray[1].replaceAll("\"", ""));
		        	
		        	if(cellToPathObjHashMap.containsKey(String.valueOf(cellId)+"_"+String.valueOf(fov))) {
		        		PathObject c = cellToPathObjHashMap.get(String.valueOf(cellId)+"_"+String.valueOf(fov));
			        	MeasurementList pathObjMeasList = c.getMeasurementList();
		        		
		        		for(int i = 2; i < exprMatNextRecordArray.length; i ++) {
			        		if(!params.getBooleanParameterValue("inclNegCtrlProbe") && (exprMatHeaders[i].replaceAll("\"", "").startsWith("NegPrb"))) continue;
			        		
			        		pathObjMeasList.put("cosmx:cell_transcript:"+exprMatHeaders[i].replaceAll("\"", ""), Double.parseDouble(exprMatNextRecordArray[i]));  
			        	}
		        		
		        		pathObjMeasList.close();
		        		
		        		if(params.getBooleanParameterValue("consolToAnnot") && hierarchy.getRootObject() != c.getParent()) {
			        		MeasurementList parentPathObjMeasList = c.getParent().getMeasurementList();
			        		
			        		for(int f = 0; f < (exprMatNextRecordArray.length-2); f ++) {	
			        			if(!params.getBooleanParameterValue("inclNegCtrlProbe") && (exprMatNextRecordArray[f].replaceAll("\"", "").startsWith("NegPrb"))) continue;
			        			
			        			double oldVal = 
			        					parentPathObjMeasList.containsKey("cosmx:spot_transcript:"+exprMatNextRecordArray[f])? 
			        					parentPathObjMeasList.get("cosmx:spot_transcript:"+exprMatNextRecordArray[f]): 
			        					0.0;
			        	
				        		parentPathObjMeasList.put("cosmx:spot_transcript:"+exprMatNextRecordArray[f], Double.parseDouble(exprMatNextRecordArray[f])+oldVal);  
				        	}
			        		
				        	parentPathObjMeasList.close();
			        	}
			        }
		        }
		        
				if(params.getBooleanParameterValue("removeUnlabeledCells")) {
					for(PathObject c: pathObjectList) {
//						if(c.getPathClass() == null) {
//							c.getParent().removeChildObject(c);
//						}
						
						MeasurementList pathObjMeasList = c.getMeasurementList();
			        	if(!pathObjMeasList.containsKey("cosmx:cell:cell_id")) {
			        		c.getParent().removeChildObject(c);
			        	}
					}
				}

		        hierarchy.getSelectionModel().setSelectedObject(null);
			}
			catch(Exception e) {	
				Dialogs.showErrorMessage("Error", e.getMessage());
				lastResults =  e.getMessage();
				logger.error(lastResults);
				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			}				
			
			if (Thread.currentThread().isInterrupted()) {
				Dialogs.showErrorMessage("Warning", "Interrupted!");
				lastResults =  "Interrupted!";
				logger.error(lastResults);
				
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
	protected void preprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
		if(params.getStringParameterValue("cosmxDir").isBlank()) {
		
			File cosmxDir = FileChoosers.promptForDirectory("CosMx directory", new File(cosmxAntnCosmxFldrProp.get()));
			if (cosmxDir != null) {
				cosmxAntnCosmxFldrProp.set(cosmxDir.toString());
			}
			else {
				Dialogs.showErrorMessage("Warning", "No CosMx directory is selected!");
				lastResults =  "No CosMx directory is selected!";
				logger.warn(lastResults);
			}
		}
		else {
			cosmxAntnCosmxFldrProp.set(params.getStringParameterValue("cosmxDir"));
		}
	};
	
	
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
		tasks.add(DetectionPluginTools.createRunnableTask(new AnnotationLoader(), getParameterList(imageData), imageData, parentObject));
	}


	@Override
	protected Collection<? extends PathObject> getParentObjects(ImageData<BufferedImage> imageData) {	
		PathObjectHierarchy hierarchy = imageData.getHierarchy();
		if (hierarchy.getTMAGrid() == null)
			return Collections.singleton(hierarchy.getRootObject());
		
		return hierarchy.getSelectionModel().getSelectedObjects().stream().filter(p -> p.isTMACore()).collect(Collectors.toList());
	}


	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		// TODO: Re-allow taking an object as input in order to limit bounds
		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
//		List<Class<? extends PathObject>> list = new ArrayList<>();
//		list.add(TMACoreObject.class);
//		list.add(PathRootObject.class);
//		return list;
		return Arrays.asList(
				PathAnnotationObject.class,
				TMACoreObject.class
				);		
	}

}
