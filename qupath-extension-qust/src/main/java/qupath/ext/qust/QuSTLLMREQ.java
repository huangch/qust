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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.fx.dialogs.Dialogs;
//import qupath.fx.dialogs.FileChoosers;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.images.ImageData;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.roi.interfaces.ROI;
import qupath.lib.scripting.QP;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.opencsv.CSVWriter;

/**
 * Plugin for LLM
 * 
 * @author Chao Hui Huang
 *
 */
public class QuSTLLMREQ extends AbstractDetectionPlugin<BufferedImage> {
	
	private static Logger logger = LoggerFactory.getLogger(QuSTLLMREQ.class);
	
	private static ParameterList params;
	
	private static String lastResults = null;
	private static QuSTSetup qustSetup = QuSTSetup.getInstance();
	private static AtomicInteger hackDigit = new AtomicInteger(0);
	
	/**
	 * Constructor.
	 * @throws Exception 
	 */
	public QuSTLLMREQ() throws Exception {
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
			.addTitleParameter("Discovering Spatial Insights based on Human Languages using LLM")		
			.addStringParameter("prompt", "Prompt", "The adaptive immune response is a critical biological process that involves the activation of T cells, includi{\\tiny }ng both alpha-beta and gamma-delta T cells, through the T cell receptor signaling pathway. This process is facilitated by cell surface receptor signaling pathways and is crucial for the body's defense against pathogens. Dendritic cell chemotaxis plays a significant role in this process, guiding immune cells to the site of infection. The activation of T cells also leads to the positive regulation of vasculature development, promoting blood vessel growth and aiding in the delivery of immune cells. Additionally, the immune response involves the production of granzymes, which initiate programmed cell death in infected cells. The negative regulation of the T cell apoptotic process ensures the survival of these crucial immune cells. Cell adhesion is another important aspect of the immune response, allowing cells to bind to each other and to the extracellular matrix. The interleukin-15-mediated signaling pathway and the positive regulation of interleukin-2 production are involved in the proliferation and differentiation of T cells. Lastly, the inflammatory response, a key component of the immune response, helps to eliminate pathogens and repair tissue damage. From a clinical perspective, understanding these processes can provide insights into the development of therapies for immune-related diseases.", "Prompt.")
			.addBooleanParameter("BP", "Include GO terms of biological processes?", true, "Include GO terms of biological processes?")
			.addBooleanParameter("MF", "Include GO terms of molecular functions?", true, "Include GO terms of molecular functions?")
			.addBooleanParameter("CC", "Include GO terms of cellular components?", true, "Include GO terms of cellular components?")
			.addIntParameter("topg", "No. of Top Key Genes", 10, "gene(s)", "No. of Top Key Genes.")
			.addBooleanParameter("all", "All samples?", true, "All samples?")
			.addEmptyParameter("...or...")
			.addIntParameter("samplingNum", "Sampling Size (0: all, default: 10000)", 10000, "objects(s)", "Number of objects for LLM analysis. A larger number resuls more time and space cosuming.")
			.addStringParameter("label", "Label", "llm_interp", "Label.")
			.addDoubleParameter("scale", "Scale", 100000.0, null, "Scale.")
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {

		
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
				/*
	             * Generate cell masks with their labels
	             */
				
				if(params.getStringParameterValue("prompt").isBlank()) throw new Exception("No prompt");
				if(params.getStringParameterValue("label").isBlank()) throw new Exception("No label");
				if(!params.getBooleanParameterValue("BP") && !params.getBooleanParameterValue("MF") && !params.getBooleanParameterValue("CC")) throw new Exception("Must include least one of BP, MF, and/or CC.");
				
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
				List<PathObject> samplingPathObjects = params.getBooleanParameterValue("all")? allDetectionPathObjectList: allDetectionPathObjectList.subList(0, params.getIntParameterValue("samplingNum"));
				
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
		        veRunner = new VirtualEnvironmentRunner(qustSetup.getEnvironmentNameOrPath(), qustSetup.getEnvironmentType(), QuSTLLMREQ.class.getSimpleName());
			
		        // This is the list of commands after the 'python' call
		        String script_path = Paths.get(qustSetup.getSptx2ScriptPath(), "llm.py").toString();
				
				List<String> QuSTArguments = new ArrayList<>(Arrays.asList("-W", "ignore", script_path, "query", dataPathString, resultPathString));
				
				if(params.getBooleanParameterValue("BP")) QuSTArguments.add("-bp");
				if(params.getBooleanParameterValue("MF")) QuSTArguments.add("-mf");
				if(params.getBooleanParameterValue("CC")) QuSTArguments.add("-cc");
				
				QuSTArguments.add("-p");
		        QuSTArguments.add("\""+params.getStringParameterValue("prompt").replace("\"","\\\"")+"\"");
				
		        QuSTArguments.add("-g");
		        QuSTArguments.add(Integer.toString(params.getIntParameterValue("topg")));

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
				
				Map<String, Double> ve_results = gson.fromJson(jsonObject.get("results"), new TypeToken<Map<String, Double>>(){}.getType());
				
				
				samplingPathObjects.parallelStream().forEach(o -> {
					if(ve_results.containsKey(o.getID().toString())) {
						double v = ve_results.get(o.getID().toString());
						MeasurementList pathObjMeasList = o.getMeasurementList();
						pathObjMeasList.put( params.getStringParameterValue("label"), v*params.getDoubleParameterValue("scale"));
					}
					
				});
				
				
				if(ve_results == null) throw new Exception("classification.py returned null");
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
//	@Override
//	protected void postprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
//	}
	
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
