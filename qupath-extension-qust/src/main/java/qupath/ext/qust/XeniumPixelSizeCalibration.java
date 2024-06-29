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
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.IntegerProperty;
import javafx.geometry.Point2D;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerMetadata;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.interfaces.ROI;

import java.io.File;
import java.io.FileWriter;

import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.Math;

/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class XeniumPixelSizeCalibration extends AbstractDetectionPlugin<BufferedImage> {
	
	final private static Logger logger = LoggerFactory.getLogger(XeniumPixelSizeCalibration.class);
	
	final private StringProperty xnumPxlSzCalXenOutFldrProp = PathPrefs.createPersistentPreference("xnumPxlSzCalXenOutFldr", ""); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	final private DoubleProperty xnumPxlSzCalDapiImgPxlSizeProp = PathPrefs.createPersistentPreference("xnumPxlSzCalDapiImgPxlSize", 0.2125); // 0.2125
	final private IntegerProperty xnumPxlSzCalImgSrsProp = PathPrefs.createPersistentPreference("xnumPxlSzCalImgSrsProp", 3); // 0.2125
	final private StringProperty xnumPxlSzCalAffineMtxProp = PathPrefs.createPersistentPreference("xnumPxlSzCalAffineMtx", "1.0, 0.0, 0.0, 0.0, 1.0, 0.0"); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	
	private ParameterList params;

	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public XeniumPixelSizeCalibration() {
		params = new ParameterList()
			.addStringParameter("xeniumOutFolder", "Xenium output folder", xnumPxlSzCalXenOutFldrProp.get(), "Xenium output folder")
			.addDoubleParameter("dapiImgPxlSize", "DAPI image pixel size", xnumPxlSzCalDapiImgPxlSizeProp.get(), GeneralTools.micrometerSymbol(), "Spot diameter")		
			.addIntParameter("imgSeries", "Image Series", xnumPxlSzCalImgSrsProp.get(), null, "Image Series")		
			.addStringParameter("affineMtx", "Affine matrix", xnumPxlSzCalAffineMtxProp.get(), "Affine matrix")
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			xnumPxlSzCalDapiImgPxlSizeProp.set(params.getDoubleParameterValue("dapiImgPxlSize")); 
			xnumPxlSzCalImgSrsProp.set(params.getIntParameterValue("imgSeries")); 
			xnumPxlSzCalAffineMtxProp.set(params.getStringParameterValue("affineMtx"));
			
			final ImageServer<BufferedImage> server = imageData.getServer();				
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();
			
			final ArrayList<PathObject> resultPathObjectList = new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			
			try {
				final double dapiImagePixelSizeMicrons = params.getDoubleParameterValue("dapiImgPxlSize");
				final int imgSeries = params.getIntParameterValue("imgSeries");
				String affineMtxStr = params.getStringParameterValue("affineMtx");
				
				affineMtxStr = affineMtxStr.replaceAll("Transformation Matrix: AffineTransform","");
				affineMtxStr = affineMtxStr.replaceAll("\\[|\\]","");
				affineMtxStr = affineMtxStr.replaceAll("","");
				
	            final Double[] affineMtx = Arrays.stream(affineMtxStr.split(","))
	            		.map(Double::parseDouble)
	            		.toArray(Double[]::new);
				
	            final double scale = 1.0; // Math.pow(2.0, (double)(imgSeries-1.0));
			        
	        	final double d0X = affineMtx[0] * 0.0 + affineMtx[1] * 0.0 + affineMtx[2] * scale;
	        	final double d0Y = affineMtx[3] * 0.0 + affineMtx[4] * 0.0 + affineMtx[5] * scale;
	        	
	        	final double dXX = affineMtx[0] * 1.0 + affineMtx[1] * 0.0 + affineMtx[2] * scale;
	        	final double dXY = affineMtx[3] * 1.0 + affineMtx[4] * 0.0 + affineMtx[5] * scale;
	        	final double dX = dapiImagePixelSizeMicrons/(new Point2D(d0X, d0Y).distance(new Point2D(dXX, dXY)));
	        	
	        	final double dYX = affineMtx[0] * 0.0 + affineMtx[1] * 1.0 + affineMtx[2] * scale;
	        	final double dYY = affineMtx[3] * 0.0 + affineMtx[4] * 1.0 + affineMtx[5] * scale;
	        	final double dY = dapiImagePixelSizeMicrons/(new Point2D(d0X, d0Y).distance(new Point2D(dYX, dYY)));
	        	
	        	ImageServerMetadata metadataNew = new ImageServerMetadata.Builder(server.getMetadata())
	        			.pixelSizeMicrons(dX, dY)
	    				.build();
	    		
	        	if (!server.getMetadata().equals(metadataNew)) imageData.updateServerMetadata(metadataNew);
	        	
	        	// JSON object. Key value pairs are unordered. JSONObject supports java.util.Map interface.
	            final JSONObject jsonObj = new JSONObject();
	            jsonObj.put("dapi_pixel_size", dapiImagePixelSizeMicrons);
	            jsonObj.put("dapi_image_series", imgSeries);
	            final JSONArray jsonAffineMatrix = new JSONArray();
	            Arrays.asList(affineMtx).forEach(v -> jsonAffineMatrix.put(v));
	            jsonObj.put("affine_matrix", jsonAffineMatrix);
	            
	            try {
	                // Constructs a FileWriter given a file name, using the platform's default charset
	            	final String xnumOutFldr = xnumPxlSzCalXenOutFldrProp.get();
	            	final String affineMtxFilePath = Paths.get(xnumOutFldr, "affine_matrix.json").toString();
	                final FileWriter file = new FileWriter(affineMtxFilePath);
	                file.write(jsonObj.toString());
	                file.flush();
                    file.close();
	            } catch (IOException e) {
	                e.printStackTrace();
	            }  
			}
			catch(Exception e) {	
				lastResults = e.getMessage();
				logger.error(lastResults);
			}				
			
			if (Thread.currentThread().isInterrupted()) {
				lastResults =  "Interrupted!";
				logger.warn(lastResults);
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
		if(params.getStringParameterValue("xeniumOutFolder").isBlank()) {
		
			final File xnumDir = Dialogs.promptForDirectory("Xenium directory", new File(xnumPxlSzCalXenOutFldrProp.get()));
			
			if (xnumDir != null) {
				xnumPxlSzCalXenOutFldrProp.set(xnumDir.toString());
			}
			else {
				Dialogs.showErrorMessage("Warning", "No Xenium directory is selected!");
				lastResults =  "No Xenium directory is selected!";
				logger.warn(lastResults);
			}
		}
		else {
			xnumPxlSzCalXenOutFldrProp.set(params.getStringParameterValue("xeniumOutFolder"));
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
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(TMACoreObject.class);
		list.add(PathRootObject.class);
		return list;
	}

}
