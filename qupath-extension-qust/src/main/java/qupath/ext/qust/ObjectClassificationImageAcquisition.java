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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;
import org.imgscalr.Scalr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Sampling images of detected objects and store into the specific folder.
 * 
 * @author Chao Hui Huang
 *
 */
public class ObjectClassificationImageAcquisition extends AbstractDetectionPlugin<BufferedImage> {

	final private static Logger logger = LoggerFactory.getLogger(ObjectClassificationImageAcquisition.class);

	private ParameterList params;
	private String lastResults = null;

	private static final StringProperty objClsImgAcqDistDirProp = PathPrefs.createPersistentPreference("objClsImgAcqDistDir", "");
	private static final DoubleProperty objClsImgAcqMPPProp = PathPrefs.createPersistentPreference("objClsImgAcqMPP",0.21233);
	private static final IntegerProperty objClsImgAcqSamplingSizeProp = PathPrefs.createPersistentPreference("objClsImgAcqSamplingSize", 36);
	private static final BooleanProperty objClsImgAcqAllSamplesProp = PathPrefs.createPersistentPreference("objClsImgAcqAllSamplesProp", true);
	private static final IntegerProperty objClsImgAcqSamplingNumProp = PathPrefs.createPersistentPreference("objClsImgAcqSamplingNum", 0);
	private static QuSTSetup qustSetup = QuSTSetup.getInstance();
	
	/**
	 * Constructor.
	 */
	public ObjectClassificationImageAcquisition() {
		params = new ParameterList()
				.addStringParameter("distFolder", "Distination Folder", objClsImgAcqDistDirProp.get(), "Distination Folder")
				.addEmptyParameter("").addEmptyParameter("Reampling using...")
				.addBooleanParameter("normalization", "Normalization (default: yes)", true, "Normalization (default: no)")
				.addBooleanParameter("dontResampling", "Do not rescaling image (default: yes)", true, "Do not rescaling image (default: yes)")
				.addEmptyParameter("...or...")
				.addDoubleParameter("MPP", "pixel size", objClsImgAcqMPPProp.get(), GeneralTools.micrometerSymbol(), "Pixel Size")
				.addEmptyParameter("")
				.addIntParameter("samplingSize", "Sampling Size", objClsImgAcqSamplingSizeProp.get(), "pixel(s)", "Sampling Size")
				.addBooleanParameter("allSamples", "Include all samples (default: yes)", objClsImgAcqAllSamplesProp.get(), "Include all samples? (default: yes)")
				.addEmptyParameter("...or...")
				.addIntParameter("samplingNum", "Maximal Sampling Number", objClsImgAcqSamplingNumProp.get(), "objects(s)", "Maximal Sampling Number")
				;
	}

	
	class DetectedObjectImageSampling implements ObjectDetector<BufferedImage> {

		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			objClsImgAcqMPPProp.set(params.getDoubleParameterValue("MPP"));
			objClsImgAcqSamplingSizeProp.set(params.getIntParameterValue("samplingSize"));
			objClsImgAcqAllSamplesProp.set(params.getBooleanParameterValue("allSamples"));
			objClsImgAcqSamplingNumProp.set(params.getIntParameterValue("samplingNum"));
			
			final PathObjectHierarchy hierarchy = imageData.getHierarchy();

			try {
				final List<PathObject> selectedAnnotationPathObjectList = Collections
						.synchronizedList(hierarchy.getSelectionModel().getSelectedObjects().stream()
								.filter(e -> e.isAnnotation()).collect(Collectors.toList()));

				if (selectedAnnotationPathObjectList.isEmpty())
					throw new Exception("Missed selected annotations");

				final ImageServer<BufferedImage> server = (ImageServer<BufferedImage>) imageData.getServer();
				final String serverPath = server.getPath();

				final double imageMPP = server.getPixelCalibration().getAveragedPixelSizeMicrons();

				final double scalingFactor = params.getBooleanParameterValue("dontResampling") ? 1.0: params.getDoubleParameterValue("MPP") / imageMPP;
						
				final int samplingFeatureSize = (int) (0.5 + scalingFactor * params.getIntParameterValue("samplingSize"));

				final AtomicBoolean success = new AtomicBoolean(true);

				final Path locationPath = Paths.get(objClsImgAcqDistDirProp.get());
				if (!Files.exists(locationPath))
					new File(locationPath.toString()).mkdirs();

				final List<PathObject> allPathObjects = Collections.synchronizedList(new ArrayList<PathObject>());
				
				selectedAnnotationPathObjectList.stream().forEach(p -> {
					allPathObjects.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()));
				});

				Collections.shuffle(allPathObjects);

				final int samplingNum = params.getIntParameterValue("samplingNum") == 0
						|| params.getIntParameterValue("samplingNum") > allPathObjects.size() 
						|| params.getBooleanParameterValue("allSamples") 
						? allPathObjects.size()
								: params.getIntParameterValue("samplingNum");
				final List<PathObject> samplingPathObjects = Collections
						.synchronizedList(allPathObjects.subList(0, samplingNum));

				IntStream.range(0, samplingPathObjects.size()).parallel().forEach(i -> {
					final PathObject objObject = samplingPathObjects.get(i);
					final ROI objRoi = objObject.getROI();

					final int x0 = (int) (0.5 + objRoi.getCentroidX() - ((double) samplingFeatureSize / 2.0));
					final int y0 = (int) (0.5 + objRoi.getCentroidY() - ((double) samplingFeatureSize / 2.0));
					final RegionRequest objRegion = RegionRequest.createInstance(serverPath, 1.0, x0, y0,
							samplingFeatureSize, samplingFeatureSize);

					try {
						final BufferedImage imgContent = (BufferedImage) server.readRegion(objRegion);
						final BufferedImage imgBuf = new BufferedImage(imgContent.getWidth(), imgContent.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
						
						imgBuf.getGraphics().drawImage(imgContent, 0, 0, null);

						final BufferedImage imgSampled = params.getBooleanParameterValue("dontResampling") ? imgBuf
								: Scalr.resize(imgBuf, params.getIntParameterValue("samplingSize"));
						
						final String format = qustSetup.getImageFileFormat().strip();
						final String fileExt = format.charAt(0) == '.' ? format.substring(1) : format;
						final Path imageFilePath = Paths.get(locationPath.toString(), objObject.getID().toString() + "." + fileExt);

						final File imageFile = new File(imageFilePath.toString());
						ImageIO.write(imgSampled, fileExt, imageFile);
					} catch (Exception e) {
						success.set(false);
						e.printStackTrace();
					}
				});

				if (!success.get())
					throw new Exception("Something went wrong");
				success.set(true);
			} catch (Exception e) {
				// TODO Auto-generated catch block
				Dialogs.showErrorMessage("Error", e.getMessage());
				e.printStackTrace();
			} finally {
				System.gc();
			}

			return hierarchy.getAnnotationObjects();
		}

		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}

	}
	
	@Override
	protected void preprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		if (params.getStringParameterValue("distFolder").isBlank()) {
			final File objClsImgAcqDistDirFp = Dialogs.promptForDirectory("Output directory", new File(objClsImgAcqDistDirProp.get()));

			if (objClsImgAcqDistDirFp != null) {
				objClsImgAcqDistDirProp.set(objClsImgAcqDistDirFp.toString());
			} else {
				Dialogs.showErrorMessage("Warning", "No output directory is selected!");
				lastResults = "No output directory is selected!";
				logger.warn(lastResults);
			}
		} else {
			objClsImgAcqDistDirProp.set(params.getStringParameterValue("distFolder"));
		}
	};

	
	@Override
	protected void postprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		if(getParameterList(imageData).getBooleanParameterValue("normalization")) {		
			try {
				final String uuid = UUID.randomUUID().toString().replace("-", "");
				final Path resultPath = Files.createTempFile("qust-classification_result-" + uuid + "-", ".json");
		        final String resultPathString = resultPath.toAbsolutePath().toString();
		        resultPath.toFile().deleteOnExit();
		        
				// Create command to run
		        VirtualEnvironmentRunner veRunner;
		        veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), ObjectClassificationImageAcquisition.class.getSimpleName(), qustSetup.getSptx2ScriptPath());
			
		        // This is the list of commands after the 'python' call
				final String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "classification.py").toString();
				
				List<String> qustArguments = new ArrayList<>(Arrays.asList("-W", "ignore", script_path, "normalize", resultPathString));
				
		        qustArguments.add("--image_path");

		        qustArguments.add(objClsImgAcqDistDirProp.get().trim().contains(" ")?  "\"" + objClsImgAcqDistDirProp.get().trim() + "\"": objClsImgAcqDistDirProp.get().trim());
		        veRunner.setArguments(qustArguments);
		
		        // Finally, we can run Cellpose
		        final String[] logs = veRunner.runCommand();
		        
		        for (String log : logs) logger.info(log);
		        
		        final FileReader resultFileReader = new FileReader(new File(resultPathString));
				final BufferedReader bufferedReader = new BufferedReader(resultFileReader);
				final Gson gson = new Gson();
				final JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
		        final Boolean ve_success = gson.fromJson(jsonObject.get("success"), new TypeToken<Boolean>(){}.getType());
		        resultPath.toFile().delete();
		        
		        assert ve_success: "classification.py returned failed";
			} catch (Exception e) {
				Dialogs.showErrorMessage("Error", e.getMessage());
				e.printStackTrace();
			} finally {
				System.gc();
			}
		}
	}
	
	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		// boolean micronsKnown =
		// imageData.getServer().getPixelCalibration().hasPixelSizeMicrons();
		// params.setHiddenParameters(!micronsKnown, "requestedPixelSizeMicrons",
		// "minAreaMicrons", "maxHoleAreaMicrons");
		// params.setHiddenParameters(micronsKnown, "requestedDownsample",
		// "minAreaPixels", "maxHoleAreaPixels");

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
		tasks.add(DetectionPluginTools.createRunnableTask(new DetectedObjectImageSampling(), getParameterList(imageData), imageData, parentObject));
	}

	@Override
	protected Collection<? extends PathObject> getParentObjects(final ImageData<BufferedImage> imageData) {

		PathObjectHierarchy hierarchy = imageData.getHierarchy();
		if (hierarchy.getTMAGrid() == null)
			return Collections.singleton(hierarchy.getRootObject());

		return hierarchy.getSelectionModel().getSelectedObjects().stream().filter(p -> p.isTMACore())
				.collect(Collectors.toList());
	}

	@Override
	public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
		// TODO: Re-allow taking an object as input in order to limit bounds
		// Temporarily disabled so as to avoid asking annoying questions when run
		// repeatedly

		return Arrays.asList(PathAnnotationObject.class, TMACoreObject.class);
	}

}
