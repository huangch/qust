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

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.IntegerProperty;
import qupath.lib.common.GeneralTools;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
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

/**
 * Plugin for loading 10x Xenium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class XeniumAnnotationImageRegistrationParameters2 extends AbstractDetectionPlugin<BufferedImage> {
	
	private static Logger logger = LoggerFactory.getLogger(XeniumAnnotationImageRegistrationParameters2.class);
	
	private StringProperty xnumAnnotImgRegParamOutDirProp = PathPrefs.createPersistentPreference("xnumAnnotImgRegParamOutDir", ""); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	private IntegerProperty xnumAnnotImgRegParamSrcImgWidthProp = PathPrefs.createPersistentPreference("xnumAnnotImgRegParamSrcImgWidth", 256); // 0.2125
	private IntegerProperty xnumAnnotImgRegParamSrcImgHeightProp = PathPrefs.createPersistentPreference("xnumAnnotImgRegParamSrcImgHeight", 256); // 0.2125
	private BooleanProperty xnumAnnotImgRegParamFlipHoriProp = PathPrefs.createPersistentPreference("xnumAnnotImgRegParamFlipHori", false); // 0.2125
	private BooleanProperty xnumAnnotImgRegParamFlipVertProp = PathPrefs.createPersistentPreference("xnumAnnotImgRegParamFlipVert", false); // 0.2125
	private StringProperty xnumAnnotImgRegParamRotationProp = PathPrefs.createPersistentPreference("xnumAnnotImgRegParamRotation", "0"); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	private StringProperty xnumAnnotImgRegParamSiftMatrixProp = PathPrefs.createPersistentPreference("xnumAnnotImgRegParamSiftMatrix", "[1,0,0,0,1,0]"); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	private DoubleProperty xnumAnnotImgRegParamSourceScaleProp = PathPrefs.createPersistentPreference("xnumAnnotImgRegParamSourceScale", 1.0); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	private DoubleProperty xnumAnnotImgRegParamTargetScaleProp = PathPrefs.createPersistentPreference("xnumAnnotImgRegParamTargetScale", 1.0); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
//	private DoubleProperty xnumAnnotImgRegParamManualScaleProp = PathPrefs.createPersistentPreference("xnumAnnotImgRegParamManualScale", 1.0); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
//	private IntegerProperty xnumAnnotImgRegParamShiftXProp = PathPrefs.createPersistentPreference("xnumAnnotImgRegParamShiftX", 0); // 0.2125
//	private IntegerProperty xnumAnnotImgRegParamShiftYProp = PathPrefs.createPersistentPreference("xnumAnnotImgRegParamShiftY", 0); // 0.2125
	private DoubleProperty xnumAnnotImgRegParamDapiImgPxlSizeProp = PathPrefs.createPersistentPreference("xnumAnnotImgRegParamDapiImgPxlSize", 0.2125); // 0.2125
	
	private List<String> rotationList = List.of("-270", "-180", "-90", "0", "90", "180", "270");
	
	private ParameterList params;

	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public XeniumAnnotationImageRegistrationParameters2() {
		params = new ParameterList()
			.addStringParameter("xnumAnnotImgRegParamOutDir", "Xenium output directory", xnumAnnotImgRegParamOutDirProp.get(), "Xenium output directory")
			.addIntParameter("xnumAnnotImgRegParamSrcImgWidth", "Source image width", xnumAnnotImgRegParamSrcImgWidthProp.get(), null, "Source image width")		
			.addIntParameter("xnumAnnotImgRegParamSrcImgHeight", "Source image height", xnumAnnotImgRegParamSrcImgHeightProp.get(), null, "Source image height")		
			// .addDoubleParameter("xnumAnnotImgRegParamManualScale", "Manual Scaling factor", xnumAnnotImgRegParamManualScaleProp.get(), null, "Spot diameter")
			.addDoubleParameter("xnumAnnotImgRegParamDapiImgPxlSize", "DAPI image pixel size", xnumAnnotImgRegParamDapiImgPxlSizeProp.get(), GeneralTools.micrometerSymbol(), "Spot diameter")	
			.addDoubleParameter("xnumAnnotImgRegParamSourceScale", "Source image scaling factor", xnumAnnotImgRegParamSourceScaleProp.get(), null, "Spot diameter")		
			.addDoubleParameter("xnumAnnotImgRegParamTargetScale", "Target image scaling factor", xnumAnnotImgRegParamTargetScaleProp.get(), null, "Spot diameter")		
			.addBooleanParameter("xnumAnnotImgRegParamFlipHori", "Flip horizontally?", xnumAnnotImgRegParamFlipHoriProp.get(), "Flip horizontally?")
			.addBooleanParameter("xnumAnnotImgRegParamFlipVert", "Flip vertically?", xnumAnnotImgRegParamFlipVertProp.get(), "Flip vertically?")
			.addChoiceParameter("xnumAnnotImgRegParamRotation", "Rotated?", xnumAnnotImgRegParamRotationProp.get(), rotationList, "Rotated?")
			.addStringParameter("xnumAnnotImgRegParamSiftMatrix", "SIFT Affine matrix", xnumAnnotImgRegParamSiftMatrixProp.get(), "Affine matrix")
//			.addIntParameter("xnumAnnotImgRegParamShiftX", "Manual shift X", xnumAnnotImgRegParamShiftXProp.get(), null, "Manual Shift X")		
//			.addIntParameter("xnumAnnotImgRegParamShiftY", "Manual Shift Y", xnumAnnotImgRegParamShiftYProp.get(), null, "Manual Shift Y")		
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			xnumAnnotImgRegParamOutDirProp.set(params.getStringParameterValue("xnumAnnotImgRegParamOutDir"));
			xnumAnnotImgRegParamSrcImgWidthProp.set(params.getIntParameterValue("xnumAnnotImgRegParamSrcImgWidth"));
			xnumAnnotImgRegParamSrcImgHeightProp.set(params.getIntParameterValue("xnumAnnotImgRegParamSrcImgHeight"));
//			xnumAnnotImgRegParamManualScaleProp.set(params.getDoubleParameterValue("xnumAnnotImgRegParamManualScale"));
			xnumAnnotImgRegParamDapiImgPxlSizeProp.set(params.getDoubleParameterValue("xnumAnnotImgRegParamDapiImgPxlSize")); 
			xnumAnnotImgRegParamFlipHoriProp.set(params.getBooleanParameterValue("xnumAnnotImgRegParamFlipHori"));
			xnumAnnotImgRegParamFlipVertProp.set(params.getBooleanParameterValue("xnumAnnotImgRegParamFlipVert"));
			xnumAnnotImgRegParamRotationProp.set((String)params.getChoiceParameterValue("xnumAnnotImgRegParamRotation"));
			xnumAnnotImgRegParamSiftMatrixProp.set(params.getStringParameterValue("xnumAnnotImgRegParamSiftMatrix"));
			xnumAnnotImgRegParamSourceScaleProp.set(params.getDoubleParameterValue("xnumAnnotImgRegParamSourceScale"));
			xnumAnnotImgRegParamTargetScaleProp.set(params.getDoubleParameterValue("xnumAnnotImgRegParamTargetScale"));
//			xnumAnnotImgRegParamShiftXProp.set(params.getIntParameterValue("xnumAnnotImgRegParamShiftX"));
//			xnumAnnotImgRegParamShiftYProp.set(params.getIntParameterValue("xnumAnnotImgRegParamShiftY"));
			
			// ImageServer<BufferedImage> server = imageData.getServer();				
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			ArrayList<PathObject> resultPathObjectList = new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			
			try {
				String xnumAnnotImgRegParamOutDir = params.getStringParameterValue("xnumAnnotImgRegParamOutDir");
				int xnumAnnotImgRegParamSrcImgWidth = params.getIntParameterValue("xnumAnnotImgRegParamSrcImgWidth");
				int xnumAnnotImgRegParamSrcImgHeight = params.getIntParameterValue("xnumAnnotImgRegParamSrcImgHeight");
				double xnumAnnotImgRegParamDapiImgPxlSize = params.getDoubleParameterValue("xnumAnnotImgRegParamDapiImgPxlSize");
				boolean xnumAnnotImgRegParamFlipHori = params.getBooleanParameterValue("xnumAnnotImgRegParamFlipHori");
				boolean xnumAnnotImgRegParamFlipVert = params.getBooleanParameterValue("xnumAnnotImgRegParamFlipVert");
				String xnumAnnotImgRegParamRotation = (String)params.getChoiceParameterValue("xnumAnnotImgRegParamRotation");
				String xnumAnnotImgRegParamSiftMatrix = params.getStringParameterValue("xnumAnnotImgRegParamSiftMatrix");
				double xnumAnnotImgRegParamSourceScale = params.getDoubleParameterValue("xnumAnnotImgRegParamSourceScale");
				double xnumAnnotImgRegParamTargetScale = params.getDoubleParameterValue("xnumAnnotImgRegParamTargetScale");
//				double xnumAnnotImgRegParamManualScale = params.getDoubleParameterValue("xnumAnnotImgRegParamManualScale");
//				int xnumAnnotImgRegParamShiftX = params.getIntParameterValue("xnumAnnotImgRegParamShiftX");
//				int xnumAnnotImgRegParamShiftY = params.getIntParameterValue("xnumAnnotImgRegParamShiftY");
				
				String affineMtxStr = xnumAnnotImgRegParamSiftMatrix
						.replaceAll("Transformation Matrix: AffineTransform","")
						.replaceAll("\\[|\\]","")
						.replaceAll("","");
				
	            Double[] affineMtx = Arrays.stream(affineMtxStr.split(","))
	            		.map(Double::parseDouble)
	            		.toArray(Double[]::new);
				
	            JSONArray jsonAffineMatrix = new JSONArray();
	            Arrays.asList(affineMtx).forEach(v -> jsonAffineMatrix.put(v));
	              
	        	
	        	// JSON object. Key value pairs are unordered. JSONObject supports java.util.Map interface.
	            JSONObject jsonObj = new JSONObject();
	            jsonObj.put("xnumAnnotImgRegParamSrcImgWidth", xnumAnnotImgRegParamSrcImgWidth);
	            jsonObj.put("xnumAnnotImgRegParamSrcImgHeight", xnumAnnotImgRegParamSrcImgHeight);
	            jsonObj.put("xnumAnnotImgRegParamFlipHori", xnumAnnotImgRegParamFlipHori);
	            jsonObj.put("xnumAnnotImgRegParamFlipVert", xnumAnnotImgRegParamFlipVert);
	            jsonObj.put("xnumAnnotImgRegParamDapiImgPxlSize", xnumAnnotImgRegParamDapiImgPxlSize);
	            jsonObj.put("xnumAnnotImgRegParamRotation", xnumAnnotImgRegParamRotation);
//	            jsonObj.put("xnumAnnotImgRegParamManualScale", xnumAnnotImgRegParamManualScale);
	            jsonObj.put("xnumAnnotImgRegParamSiftMatrix", jsonAffineMatrix);	   
	            jsonObj.put("xnumAnnotImgRegParamSourceScale", xnumAnnotImgRegParamSourceScale);
	            jsonObj.put("xnumAnnotImgRegParamTargetScale", xnumAnnotImgRegParamTargetScale);
//	            jsonObj.put("xnumAnnotImgRegParamShiftX", xnumAnnotImgRegParamShiftX);
//	            jsonObj.put("xnumAnnotImgRegParamShiftY", xnumAnnotImgRegParamShiftY);
	            
	            try {
	                // Constructs a FileWriter given a file name, using the platform's default charset
	            	String xnumOutFldr = xnumAnnotImgRegParamOutDir;
	            	String affineMtxFilePath = Paths.get(xnumOutFldr, "registration_params.json").toString();
	                FileWriter file = new FileWriter(affineMtxFilePath);
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
	protected void preprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
		if(params.getStringParameterValue("xnumAnnotImgRegParamOutDir").isBlank()) {
			File xnumAnnotImgRegParamOutDir = FileChoosers.promptForDirectory("Xenium output directory", new File(xnumAnnotImgRegParamOutDirProp.get()));
			
			if (xnumAnnotImgRegParamOutDir != null) {
				xnumAnnotImgRegParamOutDirProp.set(xnumAnnotImgRegParamOutDir.toString());
			}
			else {
				Dialogs.showErrorMessage("Warning", "No Xenium output directory is selected!");
				lastResults =  "No Xenium output directory is selected!";
				logger.warn(lastResults);
			}
		}
		else {
			xnumAnnotImgRegParamOutDirProp.set(params.getStringParameterValue("xnumAnnotImgRegParamOutDir"));
		}
	};
	
	@Override
	public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "10x Genomics Xenium Image Registration Parameters";
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
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(TMACoreObject.class);
		list.add(PathRootObject.class);
		return list;
	}
}
