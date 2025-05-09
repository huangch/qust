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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.images.ImageData;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.opencsv.CSVWriter;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
/**
 * Plugin for LLM
 * 
 * @author Chao Hui Huang
 *
 */
public class QuSTLLMHKG extends AbstractDetectionPlugin<BufferedImage> {
	
	private static Logger logger = LoggerFactory.getLogger(QuSTLLMHKG.class);
	
	private static ParameterList params;
	
	private static String lastResults = null;
	private static QuSTSetup qustSetup = QuSTSetup.getInstance();
	private static AtomicInteger hackDigit = new AtomicInteger(0);

	private static String resultImagePath;
	private static String resultContent;
	
	/**
	 * Constructor.
	 * @throws Exception 
	 */
	public QuSTLLMHKG() throws Exception {
		PathObjectHierarchy hierarchy = QP.getCurrentImageData().getHierarchy();
		
        int sltdAnnotNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isAnnotation()).collect(Collectors.toList()).size();
        int sltdDetNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()).size();
        
        if(sltdAnnotNum > 0 && sltdDetNum > 0) {
        	Dialogs.showErrorMessage("Error", "Do not select both annotations and detections.");
        	throw new Exception("Do not select both annotations and detections.");
        }
        else if(sltdAnnotNum == 0 && sltdDetNum == 0) {
        	Dialogs.showErrorMessage("Error", "No annotations/detections are selected.");
        	throw new Exception("No annotations/detections are selected.");
        }
        
        params = new ParameterList()
			.addTitleParameter("High Gene Expression LLM Analysis")		
			.addStringParameter("resLoc", "Result Location", "", "Result Location")
			.addStringParameter("resName", "Report Name", "", "Report Name")
			.addBooleanParameter("display", "Show results on the screen after the process is finished?", true)
			.addBooleanParameter("bp", "include Biological Processes?", true)
			.addBooleanParameter("mf", "include Molecular Functions?", true)
			.addBooleanParameter("cc", "include Cellular Components?", true)
			.addChoiceParameter("mode", "Mode:", "high gene expression", Arrays.asList("high gene expression", "variational gene expression"), null)
			.addIntParameter("topg", "No. of Top Key Genes", 100, "gene(s)", "No. of Top Key Genes.")
			.addIntParameter("topt", "No. of Top Key Terms", 10, "term(s)", "No. of Top Key Genes.")
			.addIntParameter("samplingNum", "Sampling Size (0: all, default: 10000)", 10000, "objects(s)", "Number of objects for LLM analysis. A larger number resuls more time and space cosuming.")
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		
//		private static Window getDefaultOwner() {
//			List<Stage> modalStages = Window.getWindows().stream()
//					.filter(w -> w.isShowing() && w instanceof Stage)
//					.map(w -> (Stage)w)
//					.filter(s -> s.getModality() != Modality.NONE)
//					.collect(Collectors.toList());
//			if (modalStages.isEmpty()) {
//				var qupath = QuPathGUI.getInstance();
//				if (qupath != null)
//					return qupath.getStage();
//				return null;
//			}
//			var focussedStages = modalStages.stream()
//					.filter(s -> s.isFocused())
//					.collect(Collectors.toList());
//			if (focussedStages.size() == 1)
//				return focussedStages.get(0);
//			return null;
//		}
//		
//		public void showResultWindow(Window owner, String title, String contents, String resultImagePath, Modality modality, boolean isEditable) {
//			if (!Platform.isFxApplicationThread()) {
//				Platform.runLater(() -> showResultWindow(owner, title, contents, resultImagePath, modality, isEditable));
//				return;
//			}
////			logDeprecated();
//			logger.info("{}\n{}", title, contents);
//			Stage dialog = new Stage();
//			if (owner == null)
//				dialog.initOwner(getDefaultOwner());
//			else
//				dialog.initOwner(owner);
//			
//			dialog.initModality(modality);
//			dialog.setTitle(title);
//			dialog.setResizable(false);
//			TextArea textArea = new TextArea();
//			textArea.setPrefColumnCount(60);
//			textArea.setPrefRowCount(25);
//
//			textArea.setText(contents);
//			textArea.setWrapText(true);
//			textArea.positionCaret(0);
//			textArea.setEditable(isEditable);
//			
//			Image image = new Image("file:"+resultImagePath, true);
//			ImageView pic = new ImageView(image);
//			pic.setFitHeight(600);
//			pic.setFitWidth(600);
//			
//			GridPane grid = new GridPane();
//			grid.add(pic, 1, 1);
//			grid.add(textArea, 2, 1);
//			
//			dialog.setScene(new Scene(grid));
//			dialog.showAndWait();
//			dialog.close();
//			
//			new File(resultImagePath+".png").delete();
//		}
		
		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			
			String uuid = UUID.randomUUID().toString().replace("-", "")+hackDigit.getAndIncrement();
			
			Path dataPath = Files.createTempFile("qust-llm_data-" + uuid + "-", ".csv");
            String dataPathString = dataPath.toAbsolutePath().toString();
            dataPath.toFile().deleteOnExit();
			
            Path resultPath = Files.createTempFile("qust-llm_result-" + uuid + "-", ".json");
            String resultPathString = resultPath.toAbsolutePath().toString();
            resultPath.toFile().deleteOnExit();
    		
			try {
//				if(!params.getBooleanParameterValue("bp") && !params.getBooleanParameterValue("mf") && !params.getBooleanParameterValue("cc")) {
//					throw new Exception("At least one of BP, MF or CC should be selected!");	
//				}
					
				/*
	             * Generate cell masks with their labels
	             */
				
		        int sltdAnnotNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isAnnotation() && e.hasChildObjects()).collect(Collectors.toList()).size();
		        int sltdDetNum = hierarchy.getSelectionModel().getSelectedObjects().stream().filter(e -> e.isDetection() && !e.hasChildObjects()).collect(Collectors.toList()).size();
		        
		        List<PathObject> allDetectionPathObjectList = Collections.synchronizedList(new ArrayList<>());
		        		
		        if(sltdAnnotNum > 0 && sltdDetNum == 0) {
		        	List<PathObject> selectedAnnotationPathObjectList = 
						hierarchy
						.getSelectionModel()
						.getSelectedObjects()
						.stream()
						.filter(e -> e.isAnnotation() && e.hasChildObjects())
						.collect(Collectors.toList());
		        	
		        	selectedAnnotationPathObjectList.parallelStream().forEach(p -> {
						synchronized(allDetectionPathObjectList) {
							allDetectionPathObjectList.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()));
						}
					});
		        }
		        else if(sltdAnnotNum == 0 && sltdDetNum > 0) {
		        	allDetectionPathObjectList.addAll(
			        	hierarchy
			        	.getSelectionModel()
			        	.getSelectedObjects()
			        	.stream()
			        	.filter(e -> e.isDetection() && !e.hasChildObjects())
			        	.collect(Collectors.toList()));
		        }
		        else if(sltdAnnotNum > 0 && sltdDetNum > 0) {
//		        	Dialogs.showErrorMessage("Error", "Do not select both annotations and detections.");
		        	throw new Exception("Do not select both annotations and detections.");
		        }
		        else {
//		        	Dialogs.showErrorMessage("Error", "No annotations/detections are selected.");
		        	throw new Exception("No annotations/detections are selected.");
		        }
				
				Collections.shuffle(allDetectionPathObjectList);
		
				if(allDetectionPathObjectList.size() < params.getIntParameterValue("samplingNum") && params.getIntParameterValue("samplingNum") != 0) throw new Exception("Insufficient sample size.");
				List<PathObject> samplingPathObjects = params.getIntParameterValue("samplingNum") == 0? allDetectionPathObjectList: allDetectionPathObjectList.subList(0, params.getIntParameterValue("samplingNum"));
				
	            ObservableMeasurementTableData measTblData = new ObservableMeasurementTableData();
	    		measTblData.setImageData(imageData, imageData == null ? Collections.emptyList() : hierarchy.getObjects(null, PathDetectionObject.class));
	    		List<String> geneNames = measTblData
	    				.getMeasurementNames()
	    				.stream()
	    				.filter(e -> e.startsWith("transcript:"))
	    				.collect(Collectors.toList());

	    		
	    		String[] geneExpList = new String[1+geneNames.size()];
	    		geneExpList[0] = "object";
	    		IntStream.range(0, geneNames.size()).forEach(i -> {
	    			geneExpList[i+1] = geneNames.get(i).replaceAll("transcript:", "").trim();
	    		});
	    		
	    		CSVWriter writer = new CSVWriter(new FileWriter(dataPathString));
	    		writer.writeNext(geneExpList, false);
	    		
				samplingPathObjects.forEach(o -> {
					geneExpList[0] = o.getID().toString();
					
					Map<String, Number> objMeas = o.getMeasurements();
					
					IntStream.range(0, geneNames.size()).parallel().forEach(i -> {
		    			geneExpList[i+1] = objMeas.get(geneNames.get(i)).toString();
		    		});
					
					
					writer.writeNext(geneExpList, false);
					
					
				});
				
				writer.close();
				

				// Create command to run
		        VirtualEnvironmentRunner veRunner;
		        veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), QuSTLLMHKG.class.getSimpleName());
			
		        // This is the list of commands after the 'python' call
		        String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "llm.py").toString();
				
		        
		        
				List<String> QuSTArguments = new ArrayList<>(Arrays.asList("-W", "ignore", script_path, ((String)params.getChoiceParameterValue("mode")).equals("high gene expression")? "hkgh": "hkgd", dataPathString, resultPathString));
			
				if(params.getBooleanParameterValue("bp")) QuSTArguments.add("-bp");
				if(params.getBooleanParameterValue("mf")) QuSTArguments.add("-mf");
				if(params.getBooleanParameterValue("cc")) QuSTArguments.add("-cc");
				
		        QuSTArguments.add("-g");
		        QuSTArguments.add(Integer.toString(params.getIntParameterValue("topg")));

		        QuSTArguments.add("-t");
		        QuSTArguments.add(Integer.toString(params.getIntParameterValue("topt")));
		        
		        veRunner.setArguments(QuSTArguments);
		        
		        // Finally, we can run the command
		        String[] logs = veRunner.runCommand();
		        for (String log : logs) logger.info(log);
		        
		        FileReader resultFileReader = new FileReader(new File(resultPathString));
				BufferedReader bufferedReader = new BufferedReader(resultFileReader);
				Gson gson = new Gson();
				JsonObject jsonObject = gson.fromJson(bufferedReader, JsonObject.class);
				
				Boolean ve_success = gson.fromJson(jsonObject.get("success"), new TypeToken<Boolean>(){}.getType());
				if(!ve_success) throw new Exception("classification.py returned failed");
				
				String ve_text_response = gson.fromJson(jsonObject.get("text_response"), new TypeToken<String>(){}.getType());
				if(ve_text_response == null) throw new Exception("classification.py returned null");
				
				resultImagePath = resultPathString+".png";
				resultContent = String.join("\n", logs);
				
				if(!params.getStringParameterValue("resLoc").isBlank() || !params.getStringParameterValue("resName").isBlank()) {
					Files.copy(
							new File(resultPathString+".png").toPath(), 
							new File(Paths.get(params.getStringParameterValue("resLoc"), params.getStringParameterValue("resName")+".png").toString()).toPath()
							);
					
					PrintWriter resultText = new PrintWriter(Paths.get(params.getStringParameterValue("resLoc"), params.getStringParameterValue("resName")+".txt").toString());
					
					resultText.println(String.join("\n", logs));
					resultText.close();
				}
				
			}
			catch(Exception e) {
				Dialogs.showErrorMessage("Error", e.getMessage());
				lastResults = e.getMessage();
				logger.error(lastResults);
			}	
			finally {
				if(dataPath.toFile().exists()) dataPath.toFile().delete();
				if(resultPath.toFile().exists()) resultPath.toFile().delete();
			}
			
			if (Thread.currentThread().isInterrupted()) {
				Dialogs.showErrorMessage("Warning", "Interrupted!");
				lastResults =  "Interrupted!";
				logger.warn(lastResults);
			}
			
			return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
		}
		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
	}

//	@Override
//	protected void preprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
//		
//	}
//	
	private static Window getDefaultOwner() {
		List<Stage> modalStages = Window.getWindows().stream()
				.filter(w -> w.isShowing() && w instanceof Stage)
				.map(w -> (Stage)w)
				.filter(s -> s.getModality() != Modality.NONE)
				.collect(Collectors.toList());
		if (modalStages.isEmpty()) {
			var qupath = QuPathGUI.getInstance();
			if (qupath != null)
				return qupath.getStage();
			return null;
		}
		var focussedStages = modalStages.stream()
				.filter(s -> s.isFocused())
				.collect(Collectors.toList());
		if (focussedStages.size() == 1)
			return focussedStages.get(0);
		return null;
	}
	
	private static void showResultBrowser() {
		if (!Platform.isFxApplicationThread()) {
			Platform.runLater(() -> showResultBrowser());
			return;
		}
//		logDeprecated();
//		logger.info("{}\n{}", title, contents);
		Stage stage = new Stage();
		
		Window owner = getDefaultOwner();
		
		if (owner == null)
			stage.initOwner(getDefaultOwner());
		else
			stage.initOwner(owner);
		
		stage.initModality(Modality.APPLICATION_MODAL);
		
		stage.setTitle("JavaFX WebView Example");

        WebView webView = new WebView();

        webView.getEngine().load("file:///homeh/huangc78/ttt.html");

        VBox vBox = new VBox(webView);
        Scene scene = new Scene(vBox, 960, 600);

        stage.setScene(scene);
        stage.show();
		
	}

	
	private static void showResultWindow(String title, String contents, String resultImagePath) {
		if (!Platform.isFxApplicationThread()) {
			Platform.runLater(() -> showResultWindow(title, contents, resultImagePath));
			return;
		}
	//	logDeprecated();
		logger.info("{}\n{}", title, contents);
		Stage dialog = new Stage();
		dialog.initOwner(getDefaultOwner());
		
		dialog.initModality(Modality.APPLICATION_MODAL);
		dialog.setTitle(title);
		dialog.setResizable(false);
		TextArea textArea = new TextArea();
		textArea.setPrefColumnCount(60);
		textArea.setPrefRowCount(25);
	
		textArea.setText(contents);
		textArea.setWrapText(true);
		textArea.positionCaret(0);
		textArea.setEditable(false);
		textArea.setScrollTop(Double.MAX_VALUE); 
		
		Image image = new Image("file:"+resultImagePath, true);
		ImageView pic = new ImageView(image);
		pic.setFitHeight(600);
		pic.setFitWidth(600);
		
		GridPane grid = new GridPane();
		grid.add(pic, 1, 1);
		grid.add(textArea, 2, 1);
		
		dialog.setScene(new Scene(grid));
		dialog.showAndWait();
		dialog.close();
		
		new File(resultImagePath+".png").delete();
	}
	
	@Override
	protected void postprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
		if(params.getBooleanParameterValue("display")) showResultWindow("Result", resultContent, resultImagePath);
	}
	
	@Override
	public ParameterList getDefaultParameterList(ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "LLM";
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
//		list.add(TMACoreObject.class);
		list.add(PathRootObject.class);
		return list;		

//		return Arrays.asList(
//				PathAnnotationObject.class,
//				TMACoreObject.class
//				);
	}
}
