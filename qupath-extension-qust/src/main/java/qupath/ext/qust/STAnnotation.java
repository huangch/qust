/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2014 - 2016 The Queen's University of Belfast, Northern Ireland
 * Contact: IP Management (ipmanagement@qub.ac.uk)
 * Copyright (C) 2018 - 2020 QuPath developers, The University of Edinburgh
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
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.opencsv.CSVReader;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReaderBuilder;

import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * ST Annotation Loader
 * 
 * A plugin for loading dataset from ST Research.
 * 
 * @author Chao Hui Huang
 *
 */
public class STAnnotation extends AbstractDetectionPlugin<BufferedImage> {
	
	// final private static Logger logger = LoggerFactory.getLogger(SpTxSpatialResearchAnnotation.class);
	
	private ParameterList params;
	
	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public STAnnotation() {
		
		
		params = new ParameterList()
			// .addTitleParameter(lastResults)
			.addStringParameter("countMatrixFile", "Count Matrix File", "", "Count Matrix File")
			.addStringParameter("alignmentFile", "Alignment File", "", "Alignment File")
			.addDoubleParameter("spotDiameter", "Spot Diameter", 100, GeneralTools.micrometerSymbol(), "Spot Diameter")
			.addDoubleParameter("origMPP", "Original Microns per Pixel (0 == ignored)", 0, GeneralTools.micrometerSymbol(), "Original Microns per Pixel (0 == ignored)")	
			.addDoubleParameter("xShift", "X-Shift", 0, GeneralTools.micrometerSymbol(), "X-Shift")
			.addDoubleParameter("yShift", "Y-Shift", 0, GeneralTools.micrometerSymbol(), "X-Shift");
	}
	
	
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			final List<PathObject> pathObjects = new ArrayList<PathObject>();
			
			try {
				final ImageServer<BufferedImage> server = imageData.getServer();
				
		        final FileReader alignmentFileReader = new FileReader(params.getStringParameterValue("alignmentFile"));
		        final CSVReader alignmentReader = new CSVReader(alignmentFileReader);		        
		        final String[] alignmentNextRecord = alignmentReader.readNext();
		        alignmentReader.close();
		        final String[] transformMtxStr = alignmentNextRecord[0].split(" ");
		        final double[] transformMtx = new double[] {0, 0, 0, 0, 0, 0, 0, 0, 0};
		        transformMtx[0] = Double.parseDouble(transformMtxStr[0]);
		        transformMtx[1] = Double.parseDouble(transformMtxStr[3]);
		        transformMtx[2] = Double.parseDouble(transformMtxStr[6]);
		        transformMtx[3] = Double.parseDouble(transformMtxStr[1]);
		        transformMtx[4] = Double.parseDouble(transformMtxStr[4]);
		        transformMtx[5] = Double.parseDouble(transformMtxStr[7]);
		        transformMtx[6] = Double.parseDouble(transformMtxStr[2]);
		        transformMtx[7] = Double.parseDouble(transformMtxStr[5]);
		        transformMtx[8] = Double.parseDouble(transformMtxStr[8]);
		        
				
		        final FileReader countMatrixFileReader = new FileReader(params.getStringParameterValue("countMatrixFile"));
		        // final CSVReader countMatrixReader = new CSVReader(countMatrixFileReader);
		        
		        
		        final CSVReader countMatrixReader = new CSVReaderBuilder(countMatrixFileReader)
		        		.withSkipLines(1)
		        		.withCSVParser(
		        				new CSVParserBuilder()
		        					.withSeparator('\t')
		        					.build()
		        		).build();
		        
		        final List<String[]> countMatrixRows = countMatrixReader.readAll();
		        
		        final HashMap<String, List<Double>> countMatrixHMap = new HashMap<String, List<Double>>();
		 
		        for(int i = 0; i < countMatrixRows.size(); i ++) {
		        	final String[] xy = countMatrixRows.get(i)[0].split("x");
		        	final double x = Double.parseDouble(xy[0]);
		        	final double y = Double.parseDouble(xy[1]);
		        	final double tx = transformMtx[0]*x + transformMtx[1]*y + transformMtx[2] * 1.0;
		        	final double ty = transformMtx[3]*x + transformMtx[4]*y + transformMtx[5] * 1.0;
		        	
		        	List<Double> list = new ArrayList<Double>();
		        	list.add(tx);
		        	list.add(ty);
		        	countMatrixHMap.put(countMatrixRows.get(i)[0], list);		        	
		        }
		        	
	        	final double spotDiameter = params.getDoubleParameterValue("spotDiameter");
	        	final double imagePixelSizeMicrons = server.getPixelCalibration().getAveragedPixelSizeMicrons();
	            final double origPixelSizeMicrons = params.getDoubleParameterValue("origMPP");	            
	            final double pixelSizeRatio = origPixelSizeMicrons != 0.0? origPixelSizeMicrons / imagePixelSizeMicrons: 1.0;
	            final double xShiftMicrons = params.getDoubleParameterValue("xShift");
	            final double yShiftMicrons = params.getDoubleParameterValue("yShift");
	            
		        Set<String> barcodeSet = countMatrixHMap.keySet();

		        for(String barcode: barcodeSet) {
		        	List<Double> list = countMatrixHMap.get(barcode);
		        	
		        	
		        			
		        			
		        	final int pxl_row_in_fullres = (int)Math.round(list.get(1) * pixelSizeRatio);
		        	final int pxl_col_in_fullres = (int)Math.round(list.get(0) * pixelSizeRatio);
	        		
					final String pathObjName = barcode;
					final String pathClsName = barcode;
							
					final int rad = (int)Math.round(0.5*spotDiameter/imagePixelSizeMicrons);
					final int dia = (int)Math.round(spotDiameter/imagePixelSizeMicrons);
					final int xshift = (int)Math.round(xShiftMicrons/imagePixelSizeMicrons);
					final int yshift = (int)Math.round(yShiftMicrons/imagePixelSizeMicrons);
					
					ROI pathRoi = ROIs.createEllipseROI(pxl_col_in_fullres-rad+xshift, pxl_row_in_fullres-rad+yshift, dia, dia, null);
					
			    	final PathClass pathCls = PathClass.fromString(pathClsName);
					final PathAnnotationObject pathObj = (PathAnnotationObject) PathObjects.createAnnotationObject(pathRoi, pathCls);
			    	
			    	pathObj.setName(pathObjName);
			    	pathObj.setColor(0);
			    	
					final MeasurementList pathObjMeasList = pathObj.getMeasurementList();
					
					pathObjMeasList.close();
					pathObjects.add(pathObj);  					
	        	}    
			}
			catch(Exception e) {		
				e.printStackTrace();
			}
			
			
			
			
			
			
			
			// List<PathObject> pathObjects = convertToPathObjects(bp, minArea, smoothCoordinates, imp.getCalibration(), downsample, maxHoleArea, excludeOnBoundary, singleAnnotation, pathImage.getImageRegion().getPlane(), null);

			if (Thread.currentThread().isInterrupted())
				return null;
			
			if (pathObjects == null || pathObjects.isEmpty())
				lastResults =  "No regions detected!";
			else if (pathObjects.size() == 1)
				lastResults =  "1 region detected";
			else
				lastResults =  pathObjects.size() + " regions detected";
			
			return pathObjects;
		}
		
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
		
		
	}
	

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
//		list.add(PathAnnotationObject.class);
		list.add(PathRootObject.class);
		return list;
	}

}
