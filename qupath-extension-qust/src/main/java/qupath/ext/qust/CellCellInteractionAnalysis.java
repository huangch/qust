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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.beans.property.StringProperty;
//import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectConnections;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.interfaces.ROI;

/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class CellCellInteractionAnalysis extends AbstractDetectionPlugin<BufferedImage> {
	private static Logger logger = LoggerFactory.getLogger(CellCellInteractionAnalysis.class);
	private static QuSTSetup qustSetup = QuSTSetup.getInstance();
	private static StringProperty CCIAnalLigandReceptorProp = PathPrefs.createPersistentPreference("CCIAnalLigandReceptor", "ligand"); 
	
	private ParameterList params;
	private List<String> ligandreceptorList = List.of("ligand", "receptor");
	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public CellCellInteractionAnalysis() {
		params = new ParameterList()
			.addTitleParameter("Cell-Cell Interaction Analysis")
			.addIntParameter("layers", "No. of layers", 1, null, "No. of layers")
			.addChoiceParameter("ligand_receptor", "Ligand-based or Receptor-based", CCIAnalLigandReceptorProp.get(), ligandreceptorList, "Summary")
			.addDoubleParameter("scale", "Scale", 1000.0, null, "Scale")
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		@Override
//		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
//			PathObjectHierarchy hierarchy = imageData.getHierarchy();
//			
//			try {
//				/*
//	             * Generate cell masks with their labels
//	             */
//				
//				CCIAnalLigandReceptorProp.set((String)params.getChoiceParameterValue("ligand_receptor"));
//				
//				ObservableMeasurementTableData measTblData = new ObservableMeasurementTableData();
//				measTblData.setImageData(imageData, imageData == null ? Collections.emptyList() : hierarchy.getObjects(null, PathDetectionObject.class));
//				
//				PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
//				if(connections == null) throw new Exception("Connections generated using Delaunay clustering are required.");
//				
//				boolean lr_flag = (String)params.getChoiceParameterValue("ligand_receptor") == "ligand"? true: false;
//				
//				List<PathObject> selectedAnnotationPathObjectList = hierarchy
//					.getSelectionModel()
//					.getSelectedObjects()
//					.stream()
//					.filter(e -> e.isAnnotation() && e.hasChildObjects())
//					.collect(Collectors.toList());
//				
//				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
//				
//				List<PathObject> allDetectionPathObjectList = Collections.synchronizedList(new ArrayList<>());
//				
//				selectedAnnotationPathObjectList.stream().forEach(p -> {
//					allDetectionPathObjectList.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()));
//				});
//				
//				List<String> availGeneList = measTblData.getAllNames().stream().filter(c -> c.startsWith("transcript:")).collect(Collectors.toList());
//				List<List<String>> lrpList = new ArrayList<>();
//				String lprFilePath = qustSetup.getCciDatasetLocationPath();
//				FileReader lrpFileReader = new FileReader(new File(lprFilePath));
//				BufferedReader lrpReader = new BufferedReader(lrpFileReader);
//				lrpReader.readLine();
//				String lrpNextRecord;
//				
//				while ((lrpNextRecord = lrpReader.readLine()) != null) {
//		        	String[] lrpNextRecordArray = lrpNextRecord.split(",");
//		        	String ligand = lrpNextRecordArray[1].replaceAll("\"", "");
//		        	String receptor = lrpNextRecordArray[2].replaceAll("\"", "");
//		        		
//		        	if(availGeneList.contains("transcript:"+ligand) && availGeneList.contains("transcript:"+receptor)) {
//		        		lrpList.add(Arrays.asList(ligand, receptor));
//		        	}
//				}
//				
//				lrpReader.close();
//				
//				allDetectionPathObjectList.parallelStream().forEach(c -> { 
//					List<PathObject> last_searching_objects = Collections.synchronizedList(new ArrayList<>());
//					List<PathObject> current_searching_objects = Collections.synchronizedList(new ArrayList<>());
//					List<PathObject> connected_objects = Collections.synchronizedList(new ArrayList<>());
//					last_searching_objects.add(c);	
//						
//					for(int l = 0; l < params.getIntParameterValue("layers"); l ++) {
//						for(PathObject o: last_searching_objects) {
//							List<PathObject> neighbors = connections.getConnections(o);
//							for(PathObject n: neighbors) {
//								if(n != c && !current_searching_objects.contains(n) && !last_searching_objects.contains(n)) {
//									current_searching_objects.add(n);
//								}
//							}
//						}
//							
//						last_searching_objects.clear();
//						last_searching_objects.addAll(current_searching_objects);
//						connected_objects.addAll(current_searching_objects);
//						current_searching_objects.clear();
//					}
//						
//					Map<String,Double> cgMapProb = new HashMap<String,Double>();
//					
//					synchronized(c) {
//						MeasurementList cMeasList = c.getMeasurementList();
////						if(lrpList.stream().map(g -> cMeasList.get("transcript:"+g.get(lr_flag? 0: 1))).anyMatch(t -> t.isNaN())) return;
//						
//						List<String> cgList = cMeasList.getMeasurementNames().stream().filter(g -> g.startsWith("transcript:")).collect(Collectors.toList());
//						if(cgList.stream().map(g -> cMeasList.get(g)).anyMatch(t -> t.isNaN())) return;
//					
//						double cgSum = cgList.stream().map(g -> cMeasList.get(g)).mapToDouble(Double::doubleValue).sum();
//						if (cgSum == 0) return;
//					
//						cgList.stream().forEach(g -> cgMapProb.put(g, cMeasList.get(g)/cgSum));				
//						lrpList.stream().forEach(g -> cMeasList.put("cci:"+(String)params.getChoiceParameterValue("ligand_receptor")+":"+g.get(0)+"_"+g.get(1), 0.0));
//					}
//					
//					Map<List<String>,Double> sumBuf = new HashMap<List<String>,Double>();
//				    Map<List<String>,Boolean> flagBuf = new HashMap<List<String>,Boolean>();
//				      
//					lrpList.stream().forEach(g -> {
//						sumBuf.put(g, Double.valueOf(0.0));
//						flagBuf.put(g, Boolean.valueOf(false));
//					});
//						
//					for(PathObject d: connected_objects) {
//						MeasurementList dMeasList = d.getMeasurementList();
////						if(lrpList.stream().map(g -> dMeasList.get("transcript:"+g.get(lr_flag? 1: 0))).anyMatch(g -> g.isNaN())) continue;
//						
//						List<String> dgList = dMeasList.getMeasurementNames().stream().filter(g -> g.startsWith("transcript:")).collect(Collectors.toList());
//						if(dgList.stream().map(g -> dMeasList.get(g)).anyMatch(g -> g.isNaN())) continue;
//						
//						double dgSum = dgList.stream().map(g -> dMeasList.get(g)).mapToDouble(Double::doubleValue).sum();
//						if(dgSum == 0) continue;
//						
//						Map<String, Double> dgMapProb = dgList.stream().collect(Collectors.toMap(g -> g, g -> dMeasList.get(g)/dgSum));
//						
//						for(List<String> lrp: lrpList) {
//							Double cv = cgMapProb.get("transcript:"+lrp.get(lr_flag? 0: 1));
//							Double dv = dgMapProb.get("transcript:"+lrp.get(lr_flag? 1: 0));
//								
//							if(cv.isNaN() || dv.isNaN()) 
//								continue;
//							
//							flagBuf.put(lrp, Boolean.valueOf(true));
//							
//							Double prob = cv*dv;
//							sumBuf.put(lrp, sumBuf.get(lrp)+prob);
//						}
//					}
//						
//					synchronized(c) {
//						for(List<String> g: lrpList) {
//							double resultValue = flagBuf.get(g)? sumBuf.get(g): 0.0;
//							MeasurementList cMeasList = c.getMeasurementList();
//							cMeasList.put("cci:"+(String)params.getChoiceParameterValue("ligand_receptor")+":"+g.get(0)+"_"+g.get(1), params.getDoubleParameterValue("scale")*resultValue);
//						}
//					}
//				});
//				
//		        hierarchy.getSelectionModel().setSelectedObject(null);
//				
//			}
//			catch(Exception e) {	
//
//				Dialogs.showErrorMessage("Error", e.getMessage());
//				lastResults = e.getMessage();
//				logger.error(lastResults);
//			}				
//			
//			if (Thread.currentThread().isInterrupted()) {
//
//				Dialogs.showErrorMessage("Warning", "Interrupted!");
//				lastResults =  "Interrupted!";
//				logger.warn(lastResults);
//			}
//			
//			return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
//		}
		
		
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			   PathObjectHierarchy hierarchy = imageData.getHierarchy();
			   try {
			       CCIAnalLigandReceptorProp.set((String)params.getChoiceParameterValue("ligand_receptor"));
			       ObservableMeasurementTableData measTblData = new ObservableMeasurementTableData();
			       measTblData.setImageData(imageData, imageData == null ? Collections.emptyList() : hierarchy.getObjects(null, PathDetectionObject.class));
			       PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
			       if(connections == null) throw new Exception("Connections generated using Delaunay clustering are required.");
			       // Cache parameter values (avoid repeated method calls)
			       final boolean lr_flag = "ligand".equals(params.getChoiceParameterValue("ligand_receptor"));
			       final int layers = params.getIntParameterValue("layers");
			       final double scale = params.getDoubleParameterValue("scale");
			       final String ligandReceptorChoice = (String)params.getChoiceParameterValue("ligand_receptor");
			       // Get all detection objects efficiently using flatMap
			       List<PathObject> allDetectionPathObjects = hierarchy
			           .getSelectionModel()
			           .getSelectedObjects()
			           .parallelStream()
			           .filter(e -> e.isAnnotation() && e.hasChildObjects())
			           .flatMap(p -> p.getChildObjects().stream())
			           .filter(PathObject::isDetection)
			           .collect(Collectors.toList());
			       if(allDetectionPathObjects.isEmpty()) throw new Exception("Missed selected annotations");
			       // Pre-load available genes into HashSet for O(1) lookup
			       Set<String> availGeneSet = measTblData.getAllNames().stream()
			           .filter(c -> c.startsWith("transcript:"))
			           .collect(Collectors.toSet());
			       // Load ligand-receptor pairs efficiently with try-with-resources
			       List<List<String>> lrpList = new ArrayList<>();
			       String lprFilePath = qustSetup.getCciDatasetLocationPath();
			       try (BufferedReader lrpReader = new BufferedReader(new FileReader(lprFilePath))) {
			           lrpReader.readLine(); // Skip header
			           lrpReader.lines()
			               .map(line -> line.split(","))
			               .filter(parts -> parts.length >= 3)
			               .forEach(parts -> {
			                   String ligand = parts[1].replaceAll("\"", "");
			                   String receptor = parts[2].replaceAll("\"", "");
			                   if(availGeneSet.contains("transcript:" + ligand) &&
			                      availGeneSet.contains("transcript:" + receptor)) {
			                       lrpList.add(Arrays.asList(ligand, receptor));
			                   }
			               });
			       }
			       // Pre-compute transcript keys to avoid string concatenation in loops
			       final List<TranscriptPair> transcriptPairs = lrpList.stream()
			           .map(lrp -> new TranscriptPair(
			               "transcript:" + lrp.get(lr_flag ? 0 : 1),
			               "transcript:" + lrp.get(lr_flag ? 1 : 0),
			               "cci:" + ligandReceptorChoice + ":" + lrp.get(0) + "-" + lrp.get(1)
			           ))
			           .collect(Collectors.toList());
			       // Cache measurements and probabilities for all objects
			       final Map<PathObject, ObjectMeasurements> cachedData = allDetectionPathObjects
			           .parallelStream()
			           .collect(Collectors.toConcurrentMap(
			               pathObj -> pathObj,
			               pathObj -> {
			                   MeasurementList measList = pathObj.getMeasurementList();
			                   Map<String, Double> measurements = new HashMap<>();
			                   double totalSum = 0.0;
			                   boolean hasValidData = false;
			                   // Cache only available transcript measurements
			                   for(String gene : availGeneSet) {
			                       double value = measList.get(gene);
			                       if(!Double.isNaN(value)) {
			                           measurements.put(gene, value);
			                           totalSum += value;
			                           hasValidData = true;
			                       }
			                   }
			                   return new ObjectMeasurements(measurements, totalSum, hasValidData && totalSum > 0);
			               }
			           ));
			       // Pre-compute connection graphs to avoid repeated BFS traversals
			       final Map<PathObject, Set<PathObject>> connectionGraph = allDetectionPathObjects
			           .parallelStream()
			           .collect(Collectors.toConcurrentMap(
			               pathObj -> pathObj,
			               pathObj -> {
			                   Set<PathObject> allConnected = new HashSet<>();
			                   Set<PathObject> currentLayer = Set.of(pathObj);
			                   Set<PathObject> visited = new HashSet<>();
			                   visited.add(pathObj);
			                   for(int l = 0; l < layers; l++) {
			                       Set<PathObject> nextLayer = currentLayer.parallelStream()
			                           .flatMap(obj -> connections.getConnections(obj).stream())
			                           .filter(neighbor -> !visited.contains(neighbor))
			                           .collect(Collectors.toSet());
			                       if(nextLayer.isEmpty()) break;
			                       visited.addAll(nextLayer);
			                       allConnected.addAll(nextLayer);
			                       currentLayer = nextLayer;
			                   }
			                   return allConnected;
			               }
			           ));
			       // Main parallel computation with optimized algorithm
			       final Map<PathObject, Map<String, Double>> resultsToApply = allDetectionPathObjects
			           .parallelStream()
			           .collect(Collectors.toConcurrentMap(
			               pathObj -> pathObj,
			               pathObj -> {
			                   ObjectMeasurements cData = cachedData.get(pathObj);
			                   if(cData == null || !cData.isValid()) {
			                       return Collections.emptyMap();
			                   }
			                   Map<String, Double> results = new HashMap<>();
			                   Set<PathObject> connectedObjects = connectionGraph.get(pathObj);
			                   // Pre-initialize all CCI measurements to 0.0
			                   transcriptPairs.forEach(tp -> results.put(tp.cciKey, 0.0));
			                   if(connectedObjects.isEmpty()) {
			                       return results;
			                   }
			                   // Calculate probabilities for c (source object)
			                   Map<String, Double> cProbabilities = cData.getProbabilities();
			                   // Process each transcript pair efficiently
			                   for(TranscriptPair tp : transcriptPairs) {
			                       Double cProb = cProbabilities.get(tp.sourceKey);
			                       if(cProb == null || cProb == 0.0) continue;
			                       double totalPairScore = 0.0;
			                       boolean foundValidPair = false;
			                       // Calculate interaction scores with connected objects
			                       for(PathObject d : connectedObjects) {
			                           ObjectMeasurements dData = cachedData.get(d);
		                        	   if(dData == null || !dData.isValid()) continue;
			                           Map<String, Double> dProbabilities = dData.getProbabilities();
			                           Double dProb = dProbabilities.get(tp.targetKey);
			                           if(dProb != null && dProb > 0.0) {
			                               totalPairScore += cProb * dProb;
			                               foundValidPair = true;
			                           }
			                       }
			                       if(foundValidPair) {
			                           results.put(tp.cciKey, scale * totalPairScore);
			                       }
			                   }
			                   return results;
			               }
			           ));
			       // Apply results back to original objects (minimal synchronization)
			       resultsToApply.entrySet().parallelStream().forEach(entry -> {
			           PathObject pathObj = entry.getKey();
			           Map<String, Double> results = entry.getValue();
			           if(!results.isEmpty()) {
			               MeasurementList measList = pathObj.getMeasurementList();
			               synchronized(pathObj) {
			                   results.forEach(measList::put);
			               }
			           }
			       });
			       hierarchy.getSelectionModel().setSelectedObject(null);
			   }
			   catch(Exception e) {  
//			       Dialogs.showErrorMessage("Error", e.getMessage());
			       lastResults = e.getMessage();
			       logger.error(lastResults);
			   }        
			   if (Thread.currentThread().isInterrupted()) {
//			       Dialogs.showErrorMessage("Warning", "Interrupted!");
			       lastResults =  "Interrupted!";
			       logger.warn(lastResults);
			   }
			   return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			}
			// Helper classes for better performance and code organization
			private static class TranscriptPair {
			   final String sourceKey;
			   final String targetKey;
			   final String cciKey;
			   TranscriptPair(String sourceKey, String targetKey, String cciKey) {
			       this.sourceKey = sourceKey;
			       this.targetKey = targetKey;
			       this.cciKey = cciKey;
			   }
			}
			private static class ObjectMeasurements {
			   private final Map<String, Double> measurements;
			   private final double totalSum;
			   private final boolean valid;
			   private Map<String, Double> probabilities; // Lazy initialization
			   ObjectMeasurements(Map<String, Double> measurements, double totalSum, boolean valid) {
			       this.measurements = measurements;
			       this.totalSum = totalSum;
			       this.valid = valid;
			   }
			   boolean isValid() {
			       return valid;
			   }
			   Map<String, Double> getProbabilities() {
			       if(probabilities == null && valid) {
			           probabilities = measurements.entrySet().stream()
			               .collect(Collectors.toMap(
			                   Map.Entry::getKey,
			                   entry -> entry.getValue() / totalSum
			               ));
			       }
			       return probabilities != null ? probabilities : Collections.emptyMap();
			   }
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
		return "Cell-Cell Interaction Analysis";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}


	@Override
	public String getDescription() {
		return "Cell-Cell Interaction Analysis";
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
