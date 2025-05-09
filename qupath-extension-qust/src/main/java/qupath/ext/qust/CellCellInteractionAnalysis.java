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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.beans.property.StringProperty;
import qupath.fx.dialogs.Dialogs;
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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class CellCellInteractionAnalysis extends AbstractDetectionPlugin<BufferedImage> {
	final private static Logger logger = LoggerFactory.getLogger(CellCellInteractionAnalysis.class);
	private StringProperty CCIAnalLigandReceptorProp = PathPrefs.createPersistentPreference("CCIAnalLigandReceptor", "ligand"); 
	
	private ParameterList params;
	protected static QuSTSetup qustSetup = QuSTSetup.getInstance();
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
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			
			try {
				/*
	             * Generate cell masks with their labels
	             */
				
				CCIAnalLigandReceptorProp.set((String)params.getChoiceParameterValue("ligand_receptor"));
				
				ObservableMeasurementTableData measTblData = new ObservableMeasurementTableData();
				measTblData.setImageData(imageData, imageData == null ? Collections.emptyList() : hierarchy.getObjects(null, PathDetectionObject.class));
				
				PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
				if(connections == null) throw new Exception("Connections generated using Delaunay clustering are required.");
				
				boolean lr_flag = (String)params.getChoiceParameterValue("ligand_receptor") == "ligand"? true: false;
				
				List<PathObject> selectedAnnotationPathObjectList = hierarchy
					.getSelectionModel()
					.getSelectedObjects()
					.stream()
					.filter(e -> e.isAnnotation() && e.hasChildObjects())
					.collect(Collectors.toList());
				
				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
				
				List<PathObject> allDetectionPathObjectList = Collections.synchronizedList(new ArrayList<>());
				
				selectedAnnotationPathObjectList.parallelStream().forEach(p -> {
					allDetectionPathObjectList.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()));
				});
				
				List<String> availGeneList = measTblData.getAllNames().stream().filter(c -> c.startsWith("transcript:")).collect(Collectors.toList());
				List<List<String>> lrpList = new ArrayList<>();
				String lprFilePath = qustSetup.getCciDatasetLocationPath();
				FileReader lrpFileReader = new FileReader(new File(lprFilePath));
				BufferedReader lrpReader = new BufferedReader(lrpFileReader);
				lrpReader.readLine();
				String lrpNextRecord;
				
				while ((lrpNextRecord = lrpReader.readLine()) != null) {
		        	String[] lrpNextRecordArray = lrpNextRecord.split(",");
		        	String ligand = lrpNextRecordArray[1].replaceAll("\"", "");
		        	String receptor = lrpNextRecordArray[2].replaceAll("\"", "");
		        		
		        	if(availGeneList.contains("transcript:"+ligand) && availGeneList.contains("transcript:"+receptor)) {
		        		lrpList.add(Arrays.asList(ligand, receptor));
		        	}
				}
				
				lrpReader.close();
				
				allDetectionPathObjectList.parallelStream().forEach(c -> { 
//				for(PathObject c: allDetectionPathObjectList) {
					List<PathObject> last_searching_objects = Collections.synchronizedList(new ArrayList<>());
					List<PathObject> current_searching_objects = Collections.synchronizedList(new ArrayList<>());
					List<PathObject> connected_objects = Collections.synchronizedList(new ArrayList<>());
						
					last_searching_objects.add(c);	
						
					for(int l = 0; l < params.getIntParameterValue("layers"); l ++) {
//						last_searching_objects.parallelStream().forEach(o -> {
						for(PathObject o: last_searching_objects) {
								
							List<PathObject> neighbors = connections.getConnections(o);
							
//							if(l == 0 && params.getBooleanParameterValue("inclSelf")) {
//								neighbors.add(c);
//							}
								
//							neighbors.parallelStream().forEach(n -> {
							for(PathObject n: neighbors) {
									
								if(n != c && !current_searching_objects.contains(n) && !last_searching_objects.contains(n)) {
									synchronized(current_searching_objects) {
										current_searching_objects.add(n);
									}
								}
//							});
							}	
//						});
						}
							
						last_searching_objects.clear();
						last_searching_objects.addAll(current_searching_objects);
							
						connected_objects.addAll(current_searching_objects);
							
						current_searching_objects.clear();
					}
						
					MeasurementList cMeasList = c.getMeasurementList();
					List<String> cgList = cMeasList.getMeasurementNames().stream().filter(g -> g.startsWith("transcript:")).collect(Collectors.toList());
						
					if(lrpList.stream().map(g -> cMeasList.get("transcript:"+g.get(lr_flag? 0: 1))).anyMatch(t -> t.isNaN())) { 
//						cMeasList.close();
						
						return;
//						continue;
					}
						
					if(cgList.stream().map(g -> cMeasList.get(g)).anyMatch(t -> t.isNaN())) { 
//						cMeasList.close();
						
						return;
//						continue;
					}
						
					double cgSum = cgList.stream().map(g -> cMeasList.get(g)).mapToDouble(Double::doubleValue).sum();
					Map<String, Double> cgMap = cgList.stream().collect(Collectors.toMap(g -> g, g -> cMeasList.get(g)/cgSum));
						
					synchronized(c) {
						lrpList.stream().forEach(g -> {
							cMeasList.put("cci:"+(String)params.getChoiceParameterValue("ligand_receptor")+":"+g.get(0)+"_"+g.get(1), 0.0);
						});
						
//						cMeasList.close();
					}
						
					Map<List<String>,Double> sumBuf = Collections.synchronizedMap(new HashMap<List<String>,Double>());
				    Map<List<String>,AtomicBoolean> flagBuf = Collections.synchronizedMap(new HashMap<List<String>,AtomicBoolean>());
				      
					lrpList.stream().forEach(g -> {
						sumBuf.put(g, Double.valueOf(0.0));
						flagBuf.put(g, new AtomicBoolean(false));
					});
						
//					connected_objects.parallelStream().forEach(d -> {
					for(PathObject d: connected_objects) {
						MeasurementList dMeasList = d.getMeasurementList();
						List<String> dgList = dMeasList.getMeasurementNames().stream().filter(g -> g.startsWith("transcript:")).collect(Collectors.toList());

						if(lrpList.stream().map(g -> dMeasList.get("transcript:"+g.get(lr_flag? 1: 0))).anyMatch(g -> g.isNaN())) { 
//							dMeasList.close();
							
//							return;
							continue;
						}
							
						if(dgList.stream().map(g -> dMeasList.get(g)).anyMatch(g -> g.isNaN())) { 
//							dMeasList.close();
							
//							return;
							continue;
						}
							
						double dgSum = dgList.stream().map(g -> dMeasList.get(g)).mapToDouble(Double::doubleValue).sum();
						
						if(dgSum == 0) { 
//							dMeasList.close();
							
//							return;
							continue;
						}
						
						Map<String, Double> dgMap = dgList.stream().collect(Collectors.toMap(g -> g, g -> dMeasList.get(g)/dgSum));
							
//						lrpList.parallelStream().forEach(lrp -> {
						for(List<String> lrp: lrpList) {
								
							Double cv = cgMap.get("transcript:"+lrp.get(lr_flag? 0: 1));
							Double dv = dgMap.get("transcript:"+lrp.get(lr_flag? 1: 0));
								
							if(cv.isNaN() || dv.isNaN()) {
//								return;
								continue;
							}
								
							Double prob = cv*dv;
							flagBuf.get(lrp).set(true);
								
							sumBuf.put(lrp, sumBuf.get(lrp)+prob);
//						});
						}
							
//						dMeasList.close();
//					});
					}
						
					for(List<String> g: lrpList) {
//					lrpList.parallelStream().forEach(g -> {
							
						double resultValue = flagBuf.get(g).get()? sumBuf.get(g): 0.0;
						
						synchronized(cMeasList) {
							cMeasList.put("cci:"+(String)params.getChoiceParameterValue("ligand_receptor")+":"+g.get(0)+"_"+g.get(1), params.getDoubleParameterValue("scale")*resultValue);
						}
//					});
					}
					
					synchronized(cMeasList) {
						cMeasList.close();
					}
				});
//				}
				
		        hierarchy.getSelectionModel().setSelectedObject(null);
				
			}
			catch(Exception e) {	

				Dialogs.showErrorMessage("Error", e.getMessage());
				lastResults = e.getMessage();
				logger.error(lastResults);
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
		// TODO: Re-allow taking an object as input in order to limit bounds
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
