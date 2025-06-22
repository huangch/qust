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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.beans.property.StringProperty;
import qupath.lib.common.GeneralTools;
//import qupath.fx.dialogs.Dialogs;
//import qupath.fx.dialogs.FileChoosers;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
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
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class PseudoVisiumSpotGeneration extends AbstractDetectionPlugin<BufferedImage> {
	
	private static Logger logger = LoggerFactory.getLogger(PseudoVisiumSpotGeneration.class);
	
	private ParameterList params;
	private StringProperty pseVisSptVendorProp = PathPrefs.createPersistentPreference("pseVisSptVendor", "xenium"); 
	private List<String> vendorlList = Arrays.asList("xenium", "cosmx");
	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public PseudoVisiumSpotGeneration() {
		params = new ParameterList()
			// .addTitleParameter(lastResults)
			.addTitleParameter("10x Pseudo Visium Spot Generator")
			.addDoubleParameter("spotDiameter", "Spot Size", 55, GeneralTools.micrometerSymbol(), "Spot Diameter")			
			.addDoubleParameter("minSpotDist", "Minimal Spot Distance", 100, GeneralTools.micrometerSymbol(), "Minimal Spot Distance")			
			// .addBooleanParameter("consolToAnnot", "Consolidate transcript data to Visium-style spots? (default: false)", false, "Consolidate Transcript Data to Annotations? (default: false)")
			.addBooleanParameter("rectGridArrangement", "Rectangular grid arrangement? (default: false)", false, "Hexagonal arrangement? (default: true)")		
			.addBooleanParameter("rectShapeSpot", "Rectangular shape spot? (default: false)", false, "Round shape spot? (default: true)")	
			.addChoiceParameter("vendor", "Vendor", pseVisSptVendorProp.get(), vendorlList, "Choose the vendor that should be used for object classification")
			.addStringParameter("prefix", "Spot ID Prefix", "pseudo-spot", "Spot ID Prefix")
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			pseVisSptVendorProp.set((String)params.getChoiceParameterValue("vendor"));
			
			ImageServer<BufferedImage> server = imageData.getServer();				
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			double pixelSizeMicrons = server.getPixelCalibration().getAveragedPixelSizeMicrons();
			int imageHeight = server.getHeight();
			int imageWidth = server.getWidth();
			
			double spotDiameterMicrons = params.getDoubleParameterValue("spotDiameter");
			double minSpotDistMicrons = params.getDoubleParameterValue("minSpotDist");
			double spotDiameterPx = spotDiameterMicrons/pixelSizeMicrons;
			double minSpotDistPx = minSpotDistMicrons/pixelSizeMicrons;
			double downsample = 4.0;
			ArrayList<PathObject> resultPathObjectList = new ArrayList<PathObject>();
			
			try {
				List<PathObject> selectedAnnotationPathObjectList = new ArrayList<>();
				
				for (PathObject pathObject : hierarchy.getSelectionModel().getSelectedObjects()) {
					if (pathObject.isAnnotation())
						selectedAnnotationPathObjectList.add(pathObject);
				}	
				
				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
				
				int maskWidth = (int)Math.ceil(imageData.getServer().getWidth()/downsample);
				int maskHeight = (int)Math.ceil(imageData.getServer().getHeight()/downsample);
				
				BufferedImage pathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_BYTE_GRAY);
				Graphics2D pathObjectG2D = pathObjectImageMask.createGraphics();				
				pathObjectG2D.setBackground(Color.BLACK);
				pathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
				pathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
				
				List<PathObject> pathObjectList = new ArrayList<PathObject>();						
				
				for(PathObject p: selectedAnnotationPathObjectList) {
					pathObjectList.add(p);
				    
				    ROI roi = p.getROI().scale(1.0/downsample, 1.0/downsample);
					Shape shape = roi.getShape();
					
					pathObjectG2D.setColor(Color.WHITE);
					pathObjectG2D.fill(shape);
				}	
				
				pathObjectG2D.dispose();	

				int halfSpotDiameterPx = (int)Math.ceil(spotDiameterPx/2.0);
				int y_step = params.getBooleanParameterValue("rectShapeSpot") || params.getBooleanParameterValue("rectGridArrangement")? (int)minSpotDistPx: (int)Math.round(0.5*Math.sqrt(3)*minSpotDistPx);
				
				int row_count = 0;
				int col_count = 0;
				boolean even_row_flag = false;
				
				for(int y = halfSpotDiameterPx; y < imageHeight-halfSpotDiameterPx; y += y_step) {
					int even_row_shift = even_row_flag && !params.getBooleanParameterValue("rectGridArrangement")? halfSpotDiameterPx: 0;
					
					for(int x = halfSpotDiameterPx+even_row_shift; x < imageWidth-halfSpotDiameterPx; x += minSpotDistPx) {
						if(pathObjectImageMask.getRGB((int)Math.round(x/downsample), (int)Math.round(y/downsample)) == Color.WHITE.getRGB()) {
							ROI pathRoi = !params.getBooleanParameterValue("rectShapeSpot")? 
								ROIs.createEllipseROI(x, y, spotDiameterPx, spotDiameterPx, null):
								ROIs.createRectangleROI(x-halfSpotDiameterPx, y-halfSpotDiameterPx, spotDiameterPx, spotDiameterPx, null);
								
							// PathClass pathCls = PathClassFactory.getPathClass(params.getStringParameterValue("prefix")+"-"+Integer.toString(row_count)+"-"+Integer.toString(col_count));
							PathClass pathCls = PathClass.fromString(params.getStringParameterValue("prefix")+"-"+Integer.toString(row_count)+"-"+Integer.toString(col_count));
							PathAnnotationObject pathObj = (PathAnnotationObject) PathObjects.createAnnotationObject(pathRoi, pathCls);
							resultPathObjectList.add(pathObj); 
						}

						col_count ++;
					}

					row_count ++;
					even_row_flag = !even_row_flag;
				}
				
				resultPathObjectList.addAll(hierarchy.getRootObject().getChildObjects());
				
				hierarchy.getSelectionModel().setSelectedObject(null);
			}
			catch(Exception e) {	
//				Dialogs.showErrorMessage("Error", e.getMessage());
				lastResults = e.getMessage();
				logger.error(lastResults);
				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			}				
			
			if (Thread.currentThread().isInterrupted()) {
//				Dialogs.showErrorMessage("Warning", "Interrupted!");
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
