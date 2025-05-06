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
import java.io.File;
import java.io.FileReader;

import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.imagej.tools.IJTools;
import java.awt.Polygon;

import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import javafx.util.Callback;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
//import qupath.fx.dialogs.FileChoosers;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.awt.Color;
import java.awt.Graphics2D;

import com.opencsv.CSVReader;

import ij.gui.Roi;
import ij.plugin.filter.ThresholdToSelection;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

/**
 * Command used to create and show a suitable dialog box for loading 10x Visium Annotations.
 * 
 * @author Chao Hui Huang
 *
 */
public class VirtualEnvironmentRunnerDialog implements Runnable {
	// final private static String name = "10xGenomics Visium Annotation (XML) Importer";

	final private static Logger logger = LoggerFactory.getLogger(VirtualEnvironmentRunnerDialog.class);

	final private QuPathGUI qupath;
	
	
	public VirtualEnvironmentRunnerDialog(final QuPathGUI qupath) {
		this.qupath = qupath;
		
		run();
	}

	public Color[] generateColors(int n)
	{
	    Color[] cols = new Color[n];
	    for(int i = 0; i < n; i++)
	    {
	        cols[i] = Color.getHSBColor((float) i / (float) n, 0.85f, 1.0f);
	    }
	    return cols;
	}
	
	
	
	@Override
	public void run() {
		
		try {
			if(qupath.getImageData() == null) throw new Exception();
			if(qupath.getImageData().getServer() == null) throw new Exception();
			if(!qupath.getImageData().getServer().getPixelCalibration().hasPixelSizeMicrons()) throw new Exception();
		}
		catch(Exception e) {
			Dialogs.showErrorMessage("Error", "Please check the image properties in left panel. Most likely the pixel size is unknown.");
			logger.error(e.getMessage());
			return;
		}
		
		final Dialog<Map<String, String>> dialog = new Dialog<>();
		dialog.setTitle("Configuration");
		dialog.setHeaderText("SpTx Analysis");
		dialog.setResizable(true);
		 
		final Label spatialFileLocationLabel = new Label("Spatial Data File (.csv): ");
		final TextField spatialFileLocationText = new TextField();		
		final Button spatialFileChsrBtn = new Button("...");
        
		spatialFileChsrBtn.setOnAction(new EventHandler<ActionEvent>() {
            public void handle(ActionEvent e) {
            	final FileChooser fileChooser = new FileChooser();
                File selectedFile = (File) fileChooser.showOpenDialog(null);
                spatialFileLocationText.setText(selectedFile.toString());
            }
        });		
		
		final Label clusterFileLocationLabel = new Label("Clustering  Data File (.csv): ");
		final TextField clusterFileLocationText = new TextField();		
		final Button clusterFileChsrBtn = new Button("...");
        
		clusterFileChsrBtn.setOnAction(new EventHandler<ActionEvent>() {
            public void handle(ActionEvent e) {
            	final FileChooser fileChooser = new FileChooser();
                File selectedFile = (File) fileChooser.showOpenDialog(null);
                clusterFileLocationText.setText(selectedFile.toString());
            }
        });	
				
		final RadioButton onlyTheSpotsRdBtn = new RadioButton("Only the spots");
        final RadioButton connectClustersRdBtn = new RadioButton("Connect spots of same clusters");
        final RadioButton surroundingRegionsRdBtn = new RadioButton("Surrounding regions of the spots");
        final ToggleGroup radioGroup = new ToggleGroup();

        onlyTheSpotsRdBtn.setToggleGroup(radioGroup);
        connectClustersRdBtn.setToggleGroup(radioGroup);
        surroundingRegionsRdBtn.setToggleGroup(radioGroup);

        final CheckBox rotateMaskCkBx = new CheckBox("Rotated image");
        final CheckBox tissueRegionsCkBx = new CheckBox("Tissue regions only");
        tissueRegionsCkBx.setSelected(true);
        
        onlyTheSpotsRdBtn.setOnAction(new EventHandler<ActionEvent>() {
            public void handle(ActionEvent e) {
            	rotateMaskCkBx.setSelected(false);
            	rotateMaskCkBx.setDisable(true);
            	tissueRegionsCkBx.setDisable(false);
            }
        });	
		
        connectClustersRdBtn.setOnAction(new EventHandler<ActionEvent>() {
            public void handle(ActionEvent e) {
            	rotateMaskCkBx.setDisable(false);
            	tissueRegionsCkBx.setSelected(true);
            	tissueRegionsCkBx.setDisable(true);
            }
        });	
        
        surroundingRegionsRdBtn.setOnAction(new EventHandler<ActionEvent>() {
            public void handle(ActionEvent e) {
            	rotateMaskCkBx.setSelected(false);
            	rotateMaskCkBx.setDisable(true);
            	tissueRegionsCkBx.setDisable(false);
            }
        });	 
        
        
        
        final Label spotDiameterLabel = new Label("Spot Diameter (um)");
        final TextField spotDiameterText = new TextField("55");
        final Label minSpotDistLabel = new Label("Minimal distance between spots (um)");
        final TextField minSpotDistText = new TextField("100");
        
		
		GridPane grid = new GridPane();
		grid.add(spatialFileLocationLabel, 1, 1);
		grid.add(spatialFileLocationText, 2, 1);
		grid.add(spatialFileChsrBtn, 3, 1);
		grid.add(clusterFileLocationLabel, 1, 2);
		grid.add(clusterFileLocationText, 2, 2);
		grid.add(clusterFileChsrBtn, 3, 2);		
		
		grid.add(spotDiameterLabel, 1, 3);
		grid.add(spotDiameterText, 2, 3);
		
		grid.add(minSpotDistLabel, 1, 4);
		grid.add(minSpotDistText, 2, 4);
		
		grid.add(onlyTheSpotsRdBtn, 1, 5);
		grid.add(connectClustersRdBtn, 2, 5);
		grid.add(surroundingRegionsRdBtn, 3, 5);		
		
		grid.add(rotateMaskCkBx, 1, 6);
		grid.add(tissueRegionsCkBx, 2, 6);

		dialog.getDialogPane().setContent(grid);
		         
		ButtonType buttonTypeOk = new ButtonType("Ok", ButtonData.OK_DONE);
		dialog.getDialogPane().getButtonTypes().add(buttonTypeOk);
		 
		dialog.setResultConverter((Callback<ButtonType, Map<String, String>>) new Callback<ButtonType, Map<String, String>>() {
		    @Override
		    public Map<String, String> call(ButtonType b) {
	        	final String spatialFileName = spatialFileLocationText.getText();
	        	final String clusterFileName = clusterFileLocationText.getText();
				
	        	if (b != buttonTypeOk || spatialFileName.isEmpty() || clusterFileName.isEmpty()) {
	        		return null;
	        	}
	        	else {
		        	final Map<String, String> result = new HashMap<String, String>();
		        	
		        	result.put("spatialFile", spatialFileLocationText.getText());
		        	result.put("clusterFile", clusterFileLocationText.getText());
		        	result.put("spotDiameter", spotDiameterText.getText());
		        	result.put("minSpotDist", minSpotDistText.getText());        	
		        	
		        	if(onlyTheSpotsRdBtn.isSelected()) result.put("type", "onlyTheSpots");
		        	else if(connectClustersRdBtn.isSelected()) result.put("type", "connectClusters");
		        	else if(surroundingRegionsRdBtn.isSelected()) result.put("type", "surroundingRegions");
		        	result.put("rotate", rotateMaskCkBx.isSelected()? "true": "false");
		        	result.put("tissueOnly", tissueRegionsCkBx.isSelected()? "true": "false");
		        	
		            return result;
		        }
		    }
		});
		         
		Optional<Map<String, String>> result = dialog.showAndWait();
		         
		if (!result.isPresent()) return;
		
	
		try {
			final ImageData<BufferedImage> imageData = (ImageData<BufferedImage>)qupath.getImageData();
			
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
			final List<PathObject> pathObjList = new ArrayList<PathObject>();    		
			
	        final FileReader spatialFileReader = new FileReader(result.get().get("spatialFile"));
	        try (CSVReader spatialReader = new CSVReader(spatialFileReader)) {
				// List<List<String>> spatialRecords = new ArrayList<List<String>>();
				final HashMap<String, List<Integer>> spatialHMap = new HashMap<String, List<Integer>>();
    
				String[] spatgialNextRecord;
 
				while ((spatgialNextRecord = spatialReader.readNext()) != null) {
					List<Integer> list = new ArrayList<Integer>();
					list.add(Integer.parseInt(spatgialNextRecord[1]));
					list.add(Integer.parseInt(spatgialNextRecord[2]));
					list.add(Integer.parseInt(spatgialNextRecord[3]));
					list.add(Integer.parseInt(spatgialNextRecord[4]));
					list.add(Integer.parseInt(spatgialNextRecord[5]));
					
					spatialHMap.put(spatgialNextRecord[0], list);
				}
				
				final double spotDiameter = Double.parseDouble(result.get().get("spotDiameter"));
				final double minSpotDist = Double.parseDouble(result.get().get("minSpotDist"));
				final double imagePixelSizeMicrons = qupath.getImageData().getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
				
				final FileReader clusterFileReader = new FileReader(result.get().get("clusterFile"));
				try (CSVReader clusterReader = new CSVReader(clusterFileReader)) {
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
					final Color[] palette = generateColors(clsNum+1);
					
					Set<String> barcodeSet = spatialHMap.keySet();
						       
					if(result.get().get("type").equals("onlyTheSpots")) {
					    for(String barcode: barcodeSet) {
					    	List<Integer> list = spatialHMap.get(barcode);
					    	
					    	final int in_tissue = list.get(0);
					    	final int pxl_row_in_fullres = list.get(3);
					    	final int pxl_col_in_fullres = list.get(4);
					    	
					    	if(result.get().get("tissueOnly").equals("true") && (in_tissue == 1) || !result.get().get("tissueOnly").equals("true")) {
					    		final int cluster = analysisHMap.containsKey(barcode)? analysisHMap.get(barcode): 0;
					    		
								final String pathObjName = barcode;
								final String pathClsName = barcode;
										
								final int rad = (int)Math.ceil(0.5*spotDiameter/imagePixelSizeMicrons);
								final int dia = rad * 2 + 1;
								
								ROI pathRoi = ROIs.createEllipseROI(pxl_col_in_fullres-rad, pxl_row_in_fullres-rad, dia, dia, null);
								
						    	final PathClass pathCls = PathClass.fromString(pathClsName);
								
						    	final PathAnnotationObject pathObj = (PathAnnotationObject) PathObjects.createAnnotationObject(pathRoi, pathCls);
						    	
						    	pathObj.setName(pathObjName);
						    	pathObj.setColor(palette[cluster].getRGB());
						    	
								final MeasurementList pathObjMeasList = pathObj.getMeasurementList();
								
								pathObjMeasList.close();
								pathObjList.add(pathObj);  
					    	}
					    }
					      
					    hierarchy.addObjects(pathObjList);
					}
					else if(result.get().get("type").equals("surroundingRegions")) {
					    for(String barcode: barcodeSet) {
					    	List<Integer> list = spatialHMap.get(barcode);
					    	
					    	final int in_tissue = list.get(0);
					    	final int pxl_row_in_fullres = list.get(3);
					    	final int pxl_col_in_fullres = list.get(4);
					    	
					    	if(result.get().get("tissueOnly").equals("true") && (in_tissue == 1) || !result.get().get("tissueOnly").equals("true")) {
					    		final int cluster = analysisHMap.containsKey(barcode)? analysisHMap.get(barcode): 0;
					    		
								final String pathObjName = barcode;
								final String pathClsName = barcode;
										
								final int rad = (int)Math.ceil(0.5*spotDiameter/imagePixelSizeMicrons);
								final int dia = rad * 2 + 1;
								
								final int rad2 = (int)Math.ceil(Math.sqrt(2.0  * Math.pow(0.5*spotDiameter/imagePixelSizeMicrons, 2.0)));
								final int dia2 = rad2 * 2 + 1;
								
								final ROI spotPathRoi = ROIs.createEllipseROI(pxl_col_in_fullres-rad, pxl_row_in_fullres-rad, dia, dia, null);		
								final ROI surroundingPathRoi = ROIs.createEllipseROI(pxl_col_in_fullres-rad2, pxl_row_in_fullres-rad2, dia2, dia2, null);	
								
								final Geometry spotPathGeom = spotPathRoi.getGeometry();
								final Geometry expandedPathGeom = surroundingPathRoi.getGeometry();
								
								final Geometry surroundingPathGeom = expandedPathGeom.difference(spotPathGeom);
								final ROI surroundingPathROI = GeometryTools.geometryToROI(surroundingPathGeom, ImagePlane.getDefaultPlane());
								
						    	final PathClass surroundingPathCls = PathClass.fromString(pathClsName);
								final PathAnnotationObject surroundingPathObj = (PathAnnotationObject) PathObjects.createAnnotationObject(surroundingPathROI, surroundingPathCls);

						    	surroundingPathObj.setName(pathObjName);
						    	surroundingPathObj.setColor(palette[cluster-1].getRGB());

								pathObjList.add(surroundingPathObj);  
					    	}
					    }
					      
					    hierarchy.addObjects(pathObjList);
					}
					else { 
					    final ImageServer<BufferedImage> imageServer = imageData.getServer();
						final int imageWidth = imageServer.getWidth();
						final int imageHeight = imageServer.getHeight();

					    for(int c = 1; c < clsNum; c ++) {
					    	final BufferedImage image = new BufferedImage(imageWidth/4, imageHeight/4, BufferedImage.TYPE_BYTE_GRAY);
					    	final Graphics2D graphic = image.createGraphics();
							
					    	for(String barcode: barcodeSet) {
					        	List<Integer> list = spatialHMap.get(barcode);
					        	
					        	final int in_tissue = list.get(0);
					        	final int pxl_row_in_fullres = list.get(3);
					        	final int pxl_col_in_fullres = list.get(4);	 
					        	
					        	if(in_tissue == 1) {
					        		final Integer cluster = analysisHMap.get(barcode);
					        		final int r = (int)Math.round(Math.ceil(0.5*minSpotDist/Math.sqrt(3.0)*2.0/imagePixelSizeMicrons)/4.0);
							    	
					        		if (cluster == c) {
										final Polygon p = new Polygon();
										final int x = (int)Math.round((double)pxl_col_in_fullres/4.0);
										final int y = (int)Math.round((double)pxl_row_in_fullres/4.0);

									    for (int i = 0; i < 6; i++)								    	
										    if(result.get().get("rotate").equals("true")) {
										    	
										    	p.addPoint((int) (x + r * Math.cos((i * 2 * Math.PI / 6)+(2 * Math.PI / 12))),
										    			(int) (y + r * Math.sin((i * 2 * Math.PI / 6)+(2 * Math.PI / 12))));
										    }
										    else {
										    	p.addPoint((int) (x + r * Math.cos((i * 2 * Math.PI / 6))),
										    			(int) (y + r * Math.sin((i * 2 * Math.PI / 6))));
										    }
									    graphic.setColor(Color.WHITE);
									    graphic.fillPolygon(p);		        			
					        		}
					        	}
					    	}
					    	
					    	final ByteProcessor bp = new ByteProcessor(image);
					    	bp.setThreshold(127.5, 255, ImageProcessor.NO_LUT_UPDATE);
					    	final Roi roiIJ = new ThresholdToSelection().convert(bp);
					    	
					    	 if (roiIJ != null) {
						    	final ROI roi = IJTools.convertToROI(roiIJ, 0, 0, 4, ImagePlane.getDefaultPlane());
						    	final PathClass pathCls = PathClass.fromString("cluster-"+String.valueOf(c));
								final PathObject p = PathObjects.createAnnotationObject(roi, pathCls);
						    	
						    		hierarchy.addObject(p);
						    		hierarchy.updateObject(p, true);
						    }
					    }
					}
				}
			}
		}
		catch(Exception e) {			
		}
	}
}
