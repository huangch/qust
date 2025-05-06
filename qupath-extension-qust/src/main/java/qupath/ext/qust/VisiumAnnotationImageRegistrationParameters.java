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
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class VisiumAnnotationImageRegistrationParameters extends AbstractDetectionPlugin<BufferedImage> {
	
	private static Logger logger = LoggerFactory.getLogger(VisiumAnnotationImageRegistrationParameters.class);
	
	private StringProperty vsumAnnotImgRegParamOutDirProp = PathPrefs.createPersistentPreference("vsumAnnotImgRegParamOutDir", ""); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	private IntegerProperty vsumAnnotImgRegParamSrcImgWidthProp = PathPrefs.createPersistentPreference("vsumAnnotImgRegParamSrcImgWidth", 256); // 0.2125
	private IntegerProperty vsumAnnotImgRegParamSrcImgHeightProp = PathPrefs.createPersistentPreference("vsumAnnotImgRegParamSrcImgHeight", 256); // 0.2125
	private BooleanProperty vsumAnnotImgRegParamFlipHoriProp = PathPrefs.createPersistentPreference("vsumAnnotImgRegParamFlipHori", false); // 0.2125
	private BooleanProperty vsumAnnotImgRegParamFlipVertProp = PathPrefs.createPersistentPreference("vsumAnnotImgRegParamFlipVert", false); // 0.2125
	private StringProperty vsumAnnotImgRegParamRotationProp = PathPrefs.createPersistentPreference("vsumAnnotImgRegParamRotation", "0"); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	private StringProperty vsumAnnotImgRegParamSiftMatrixProp = PathPrefs.createPersistentPreference("vsumAnnotImgRegParamSiftMatrix", "[1,0,0,0,1,0]"); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	private DoubleProperty vsumAnnotImgRegParamSourceScaleProp = PathPrefs.createPersistentPreference("vsumAnnotImgRegParamSourceScale", 1.0); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	private DoubleProperty vsumAnnotImgRegParamTargetScaleProp = PathPrefs.createPersistentPreference("vsumAnnotImgRegParamTargetScale", 1.0); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	private DoubleProperty vsumAnnotImgRegParamManualScaleProp = PathPrefs.createPersistentPreference("vsumAnnotImgRegParamManualScale", 1.0); // 0.583631786649883, -0.003093833507169, 3976.5962855892744, 0.002910311759446, 0.583704549228862, 4045.851508970304
	private IntegerProperty vsumAnnotImgRegParamShiftXProp = PathPrefs.createPersistentPreference("vsumAnnotImgRegParamShiftX", 0); // 0.2125
	private IntegerProperty vsumAnnotImgRegParamShiftYProp = PathPrefs.createPersistentPreference("vsumAnnotImgRegParamShiftY", 0); // 0.2125
	
	private List<String> rotationList = List.of("-270", "-180", "-90", "0", "90", "180", "270");
	
	private ParameterList params;

	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public VisiumAnnotationImageRegistrationParameters() {
		params = new ParameterList()
			.addStringParameter("vsumAnnotImgRegParamOutDir", "Visium output directory", vsumAnnotImgRegParamOutDirProp.get(), "Visium output directory")
			.addIntParameter("vsumAnnotImgRegParamSrcImgWidth", "Source image width", vsumAnnotImgRegParamSrcImgWidthProp.get(), null, "Source image width")		
			.addIntParameter("vsumAnnotImgRegParamSrcImgHeight", "Source image height", vsumAnnotImgRegParamSrcImgHeightProp.get(), null, "Source image height")		
			.addDoubleParameter("vsumAnnotImgRegParamManualScale", "Manual Scaling factor", vsumAnnotImgRegParamManualScaleProp.get(), null, "Spot diameter")		
			.addDoubleParameter("vsumAnnotImgRegParamSourceScale", "Source image scaling factor", vsumAnnotImgRegParamSourceScaleProp.get(), null, "Spot diameter")		
			.addDoubleParameter("vsumAnnotImgRegParamTargetScale", "Target image scaling factor", vsumAnnotImgRegParamTargetScaleProp.get(), null, "Spot diameter")		
			.addBooleanParameter("vsumAnnotImgRegParamFlipHori", "Flip horizontally?", vsumAnnotImgRegParamFlipHoriProp.get(), "Flip horizontally?")
			.addBooleanParameter("vsumAnnotImgRegParamFlipVert", "Flip vertically?", vsumAnnotImgRegParamFlipVertProp.get(), "Flip vertically?")
			.addChoiceParameter("vsumAnnotImgRegParamRotation", "Rotated?", vsumAnnotImgRegParamRotationProp.get(), rotationList, "Rotated?")
			.addStringParameter("vsumAnnotImgRegParamSiftMatrix", "SIFT Affine matrix", vsumAnnotImgRegParamSiftMatrixProp.get(), "Affine matrix")
			.addIntParameter("vsumAnnotImgRegParamShiftX", "Manual shift X", vsumAnnotImgRegParamShiftXProp.get(), null, "Manual Shift X")		
			.addIntParameter("vsumAnnotImgRegParamShiftY", "Manual Shift Y", vsumAnnotImgRegParamShiftYProp.get(), null, "Manual Shift Y")		
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			vsumAnnotImgRegParamOutDirProp.set(params.getStringParameterValue("vsumAnnotImgRegParamOutDir"));
			vsumAnnotImgRegParamSrcImgWidthProp.set(params.getIntParameterValue("vsumAnnotImgRegParamSrcImgWidth"));
			vsumAnnotImgRegParamSrcImgHeightProp.set(params.getIntParameterValue("vsumAnnotImgRegParamSrcImgHeight"));
			vsumAnnotImgRegParamManualScaleProp.set(params.getDoubleParameterValue("vsumAnnotImgRegParamManualScale"));
			vsumAnnotImgRegParamFlipHoriProp.set(params.getBooleanParameterValue("vsumAnnotImgRegParamFlipHori"));
			vsumAnnotImgRegParamFlipVertProp.set(params.getBooleanParameterValue("vsumAnnotImgRegParamFlipVert"));
			vsumAnnotImgRegParamRotationProp.set((String)params.getChoiceParameterValue("vsumAnnotImgRegParamRotation"));
			vsumAnnotImgRegParamSiftMatrixProp.set(params.getStringParameterValue("vsumAnnotImgRegParamSiftMatrix"));
			vsumAnnotImgRegParamSourceScaleProp.set(params.getDoubleParameterValue("vsumAnnotImgRegParamSourceScale"));
			vsumAnnotImgRegParamTargetScaleProp.set(params.getDoubleParameterValue("vsumAnnotImgRegParamTargetScale"));
			vsumAnnotImgRegParamShiftXProp.set(params.getIntParameterValue("vsumAnnotImgRegParamShiftX"));
			vsumAnnotImgRegParamShiftYProp.set(params.getIntParameterValue("vsumAnnotImgRegParamShiftY"));
			
			// ImageServer<BufferedImage> server = imageData.getServer();				
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			ArrayList<PathObject> resultPathObjectList = new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			
			try {
				String vsumAnnotImgRegParamOutDir = params.getStringParameterValue("vsumAnnotImgRegParamOutDir");
				int vsumAnnotImgRegParamSrcImgWidth = params.getIntParameterValue("vsumAnnotImgRegParamSrcImgWidth");
				int vsumAnnotImgRegParamSrcImgHeight = params.getIntParameterValue("vsumAnnotImgRegParamSrcImgHeight");
				boolean vsumAnnotImgRegParamFlipHori = params.getBooleanParameterValue("vsumAnnotImgRegParamFlipHori");
				boolean vsumAnnotImgRegParamFlipVert = params.getBooleanParameterValue("vsumAnnotImgRegParamFlipVert");
				String vsumAnnotImgRegParamRotation = (String)params.getChoiceParameterValue("vsumAnnotImgRegParamRotation");
				String vsumAnnotImgRegParamSiftMatrix = params.getStringParameterValue("vsumAnnotImgRegParamSiftMatrix");
				double vsumAnnotImgRegParamSourceScale = params.getDoubleParameterValue("vsumAnnotImgRegParamSourceScale");
				double vsumAnnotImgRegParamTargetScale = params.getDoubleParameterValue("vsumAnnotImgRegParamTargetScale");
				double vsumAnnotImgRegParamManualScale = params.getDoubleParameterValue("vsumAnnotImgRegParamManualScale");
				int vsumAnnotImgRegParamShiftX = params.getIntParameterValue("vsumAnnotImgRegParamShiftX");
				int vsumAnnotImgRegParamShiftY = params.getIntParameterValue("vsumAnnotImgRegParamShiftY");
				
				String affineMtxStr = vsumAnnotImgRegParamSiftMatrix
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
	            jsonObj.put("vsumAnnotImgRegParamSrcImgWidth", vsumAnnotImgRegParamSrcImgWidth);
	            jsonObj.put("vsumAnnotImgRegParamSrcImgHeight", vsumAnnotImgRegParamSrcImgHeight);
	            jsonObj.put("vsumAnnotImgRegParamFlipHori", vsumAnnotImgRegParamFlipHori);
	            jsonObj.put("vsumAnnotImgRegParamFlipVert", vsumAnnotImgRegParamFlipVert);
	            jsonObj.put("vsumAnnotImgRegParamRotation", vsumAnnotImgRegParamRotation);
	            jsonObj.put("vsumAnnotImgRegParamManualScale", vsumAnnotImgRegParamManualScale);
	            jsonObj.put("vsumAnnotImgRegParamSiftMatrix", jsonAffineMatrix);	   
	            jsonObj.put("vsumAnnotImgRegParamSourceScale", vsumAnnotImgRegParamSourceScale);
	            jsonObj.put("vsumAnnotImgRegParamTargetScale", vsumAnnotImgRegParamTargetScale);
	            jsonObj.put("vsumAnnotImgRegParamShiftX", vsumAnnotImgRegParamShiftX);
	            jsonObj.put("vsumAnnotImgRegParamShiftY", vsumAnnotImgRegParamShiftY);
	            
	            try {
	                // Constructs a FileWriter given a file name, using the platform's default charset
	            	String vsumOutFldr = vsumAnnotImgRegParamOutDir;
	            	String affineMtxFilePath = Paths.get(vsumOutFldr, "registration_params.json").toString();
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
		if(params.getStringParameterValue("vsumAnnotImgRegParamOutDir").isBlank()) {
			File vsumAnnotImgRegParamOutDir = FileChoosers.promptForDirectory("Visium output directory", new File(vsumAnnotImgRegParamOutDirProp.get()));
			
			if (vsumAnnotImgRegParamOutDir != null) {
				vsumAnnotImgRegParamOutDirProp.set(vsumAnnotImgRegParamOutDir.toString());
			}
			else {
				Dialogs.showErrorMessage("Warning", "No Visium output directory is selected!");
				lastResults =  "No Visium output directory is selected!";
				logger.warn(lastResults);
			}
		}
		else {
			vsumAnnotImgRegParamOutDirProp.set(params.getStringParameterValue("vsumAnnotImgRegParamOutDir"));
		}
	};
	
	@Override
	public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "10x Genomics Visium V2 (CytAssist) Image Registration Parameters";
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
