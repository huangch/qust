/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2021 QuPath developers, The University of Edinburgh
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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.common.GeneralTools;
import qupath.lib.geom.Point2;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.roi.interfaces.ROI.RoiType;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.analysis.images.SimpleImages;

/**
 * Command to export object(s) in GeoJSON format to an output file.
 * 
 * @author Melvin Gelbard
 * @author Pete Bankhead
 * @author Chao Hui Huang
 */
// TODO make default dir the project one when choosing outFile?
public final class ExportPathDetectionObjectToOMECSVCommand {
	
	final private static Logger logger = LoggerFactory.getLogger(XeniumAnnotation.class);
	
	// Suppress default constructor for non-instantiability
	private ExportPathDetectionObjectToOMECSVCommand() {
		throw new AssertionError();
	}
	
	/**
	 * Show a dialog to export object(s) to a GeoJSON file.
	 * @param qupath
	 * @param imageData
	 */
	public static void runOMEObjectExport(QuPathGUI qupath, ImageData<BufferedImage> imageData) {
		try {
			runOMECSVExport(qupath);
		} catch (IOException e) {
			Dialogs.showErrorNotification("Export error", e.getLocalizedMessage());
		}
	}
	
	/**
	 * Run the path object OME-CSV export command.
	 * @param outFile 
	 * @return success
	 * @throws IOException 
	 */
	private static void writeCSV(String outFile, Collection<PathObject> chosenObjects, String type, ImageData<?> imageData, int downsampling, boolean excludeMeasurements) throws IOException {
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
						row.add(obj.getPathClass().toString());
						
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
						row.add(obj.getPathClass().toString());	
						
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
	
	/**
	 * Run the path object GeoJSON export command.
	 * @param qupath 
	 * @return success
	 * @throws IOException 
	 */
	public static boolean runOMECSVExport(QuPathGUI qupath) throws IOException {
		// Get ImageData
		final var imageData = qupath.getImageData();
		
		if (imageData == null)
			return false;
		
		// Get hierarchy
		final PathObjectHierarchy hierarchy = imageData.getHierarchy();
	
		final String allObjects = "All objects";
		final String selectedObjects = "Selected objects";
		final String defaultObjects = hierarchy.getSelectionModel().noSelection() ? allObjects : selectedObjects;
		
		// Params
		final var parameterList = new ParameterList()
				.addChoiceParameter("exportOptions", "Export ", defaultObjects, Arrays.asList(allObjects, selectedObjects), "Choose which objects to export - run a 'Select annotations/detections' command first if needed")
				.addChoiceParameter("exportType", "Type", "annotation", Arrays.asList("annotation", "detection"), "Type")				
				.addBooleanParameter("excludeMeasurements", "Exclude measurements", false, "Exclude object measurements during export - for large numbers of detections this can help reduce the file size")
				.addIntParameter("downsampling", "Downsampling factor (default: 2)", 2, null, "Downsampling factor for reducing OMERO loading")
				;
		
		if (!Dialogs.showParameterDialog("Export objects", parameterList))
			return false;
		
		Collection<PathObject> toProcess;
		final var comboChoice = parameterList.getChoiceParameterValue("exportOptions");
		if (comboChoice.equals(selectedObjects)) {
			if (hierarchy.getSelectionModel().noSelection()) {
				Dialogs.showErrorMessage("No selection", "No selection detected!");
				return false;
			}
			toProcess = hierarchy.getSelectionModel().getSelectedObjects();
		} else
			toProcess = hierarchy.getObjects(null, null);
		
		// Remove PathRootObject
		toProcess = toProcess.stream().filter(e -> !e.isRootObject()).collect(Collectors.toList());
		
		Collection<PathObject> chosenObjects;
		
		if(((String)parameterList.getChoiceParameterValue("exportType")).equals("annotation")) {
			chosenObjects = toProcess.stream().filter(e -> e.isAnnotation()).collect(Collectors.toList());
		}
		else {
			chosenObjects = new ArrayList<PathObject>();
			
			toProcess.stream().forEach(p -> {
//			 for (PathObject p : toProcess) {
				if (p.isAnnotation() && p.hasChildObjects())
					chosenObjects.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()));
				else if(p.isDetection()) 
					chosenObjects.add(p);
//			 }
			});
		}
		
		final File outFile;
		// Get default name & output directory
		var project = qupath.getProject();
		String defaultName = imageData.getServer().getMetadata().getName();
		if (project != null) {
			var entry = project.getEntry(imageData);
			if (entry != null)
				defaultName = entry.getImageName();
		}
		defaultName = GeneralTools.getNameWithoutExtension(defaultName);
		File defaultDirectory = project == null || project.getPath() == null ? null : project.getPath().toFile();
		while (defaultDirectory != null && !defaultDirectory.isDirectory())
			defaultDirectory = defaultDirectory.getParentFile();

		outFile = Dialogs.promptToSaveFile("Export to file", defaultDirectory, defaultName, "Gzipped OMERO-compatible Comma-Separated Values (.OGZ)", ".ogz");
		
		// If user cancels
		if (outFile == null)
			return false;
		
		final int downsampling = parameterList.getIntParameterValue("downsampling");
		final boolean excludeMeasurements = parameterList.getBooleanParameterValue("excludeMeasurements");
		writeCSV(outFile.getAbsolutePath(), chosenObjects, parameterList.getChoiceParameterValue("exportType").toString(), imageData, downsampling, excludeMeasurements);
		
		// Notify user of success
		int nObjects = toProcess.size();
		String message = nObjects == 1 ? "1 object was exported to " + outFile.getAbsolutePath() : 
			String.format("%d objects were exported to %s", nObjects, outFile.getAbsolutePath());
		Dialogs.showInfoNotification("Succesful export", message);
		
		// Get history workflow
		var historyWorkflow = imageData.getHistoryWorkflow();
		
		// args for workflow step
		Map<String, String> map = new LinkedHashMap<>();
		map.put("path", outFile.getPath());

		String method = comboChoice.equals(allObjects) ? "exportAllObjectsToOMECSV" : "exportSelectedObjectsToOMECSV";
		String methodTitle = comboChoice.equals(allObjects) ? "Export all objects" : "Export selected objects";

		String methodString = String.format("%s(%s)", 
				method, 
				"\"" + GeneralTools.escapeFilePath(outFile.getPath()) + "\"");

		historyWorkflow.addStep(new DefaultScriptableWorkflowStep(methodTitle, map, methodString));		
		return true;
	}
	
}