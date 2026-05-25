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
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.StringProperty;
import javafx.geometry.Point2D;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.roi.interfaces.ROI;

import org.apache.avro.generic.GenericRecord;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.json.JSONObject;
/**
 * Plugin for loading 10x Xenium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class XeniumAnnotation extends AbstractDetectionPlugin<BufferedImage> {
	
	private static Logger logger = LoggerFactory.getLogger(XeniumAnnotation.class);
	
	private StringProperty xnumAntnXnumFldrProp = PathPrefs.createPersistentPreference("xnumAntnXnumFldr", ""); 
	
	private ParameterList params = null;

	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public XeniumAnnotation() {	
		params = new ParameterList()
			.addTitleParameter("10X Xenium Data Loader")
			.addStringParameter("xeniumDir", "Xenium directory", xnumAntnXnumFldrProp.get(), "Xenium Out Directory")
			.addEmptyParameter("")
			.addBooleanParameter("dontTransform", "DO NOT transform? (default: false)", false, "DO NOT transform? (default: false)")		
			.addBooleanParameter("AffineTransformOnly", "Affine (linear) transform ONLY? (default: false)", false, "Affine (linear) transform ONLY? (default: false)")		
			.addBooleanParameter("removeUnlabeledCells", "Remove unlabeled cells? (default: true)", true, "Remove unlabeled cells? (default: true)")		
			.addBooleanParameter("inclGeneExpr", "Include Gene Expression? (default: true)", true, "Include Gene Expression? (default: true)")		
			.addBooleanParameter("inclBlankCodeword", "Include Blank Codeword? (default: false)", false, "Include Blank Codeword? (default: false)")
			.addBooleanParameter("inclUnassignedCodeword", "Include Unassigned Codeword? (default: false)", false, "Include Unassigned Codeword? (default: false)")
			.addBooleanParameter("inclDeprecatedCodeword", "Include Deprecated Codeword? (default: false)", false, "Include Deprecated Codeword? (default: false)")
			.addBooleanParameter("inclIntergenicRegion", "Include Intergenic Region? (default: false)", false, "Include Intergenic Region? (default: false)")
			.addBooleanParameter("inclNegCtrlCodeword", "Include Negative Control Codeword? (default: false)", false, "Include Negative Control Codeword? (default: false)")		
			.addBooleanParameter("inclNegCtrlProbe", "Include Negative Control Probe? (default: false)", false, "Include Negative Control Probe? (default: false)")		
			.addEmptyParameter("")
			.addIntParameter("maskDownsampling", "Downsampling for transcript to cell assignment", 2, null, "Downsampling for cell-transciptome assignment")			
			;
	}
	
	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		//------------------------------------------------------------------
		/**
		 * Read the number of intervals of a transformation from a file.
		 *
		 * @param filename transformation file name
		 * @return number of intervals
		 */
		public int numberOfIntervalsOfTransformation(String filename)
		{
			try {
				FileReader fr = new FileReader(filename);
				BufferedReader br = new BufferedReader(fr);
				String line;

				// Read number of intervals
				line = br.readLine();
				int lineN=1;
				StringTokenizer st=new StringTokenizer(line,"=");
				if (st.countTokens()!=2) {
					fr.close();
					logger.error("Line "+lineN+"+: Cannot read number of intervals");
					return -1;
				}
				st.nextToken();
				int intervals=Integer.valueOf(st.nextToken()).intValue();

				fr.close();
				return intervals;
			} catch (FileNotFoundException e) {
				logger.error("File not found exception" + e);
				return -1;
			} catch (IOException e) {
				logger.error("IOException exception" + e);
				return -1;
			} catch (NumberFormatException e) {
				logger.error("Number format exception" + e);
				return -1;
			}
		}

		//------------------------------------------------------------------
		/**
		 * Load a transformation from a file.
		 *
		 * @param filename transformation file name
		 * @param cx x- B-spline coefficients
		 * @param cy y- B-spline coefficients
		 */
		public void loadTransformation(String filename,
				double [][]cx, double [][]cy)
		{
			try {
				FileReader fr = new FileReader(filename);
				BufferedReader br = new BufferedReader(fr);
				String line;

				// Read number of intervals
				line = br.readLine();
				int lineN = 1;
				StringTokenizer st = new StringTokenizer(line,"=");
				if (st.countTokens()!=2)
				{
					br.close();
					fr.close();
					logger.info("Line "+lineN+"+: Cannot read number of intervals");
					return;
				}
				st.nextToken();
				int intervals=Integer.valueOf(st.nextToken()).intValue();

				// Skip next 2 lines
				line = br.readLine();
				line = br.readLine();
				lineN+=2;

				// Read the cx coefficients
				for (int i= 0; i<intervals+3; i++)
				{
					line = br.readLine(); 
					lineN++;
					st=new StringTokenizer(line);
					if (st.countTokens()!=intervals+3)
					{
						br.close();
						fr.close();
						logger.info("Line "+lineN+": Cannot read enough coefficients");
						return;
					}
					for (int j=0; j<intervals+3; j++)
						cx[i][j]=Double.valueOf(st.nextToken()).doubleValue();
				}

				// Skip next 2 lines
				line = br.readLine();
				line = br.readLine();
				lineN+=2;

				// Read the cy coefficients
				for (int i= 0; i<intervals+3; i++)
				{
					line = br.readLine(); 
					lineN++;
					st = new StringTokenizer(line);
					if (st.countTokens()!=intervals+3)
					{
						br.close();
						fr.close();
						logger.info("Line "+lineN+": Cannot read enough coefficients");
						return;
					}
					for (int j=0; j<intervals+3; j++)
						cy[i][j]=Double.valueOf(st.nextToken()).doubleValue();
				}
				fr.close();
			} catch (FileNotFoundException e) {
				logger.error("File not found exception" + e);
				return;
			} catch (IOException e) {
				logger.error("IOException exception" + e);
				return;
			} catch (NumberFormatException e) {
				logger.error("Number format exception" + e);
				return;
			}
		}

		
		/* --------------------------------------------------------------------*/
		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
			ImageServer<BufferedImage> server = imageData.getServer();				
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			ArrayList<PathObject> resultPathObjectList = new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
			
			try {
				// Load linear transformation
				
				InputStream is = Paths.get(xnumAntnXnumFldrProp.get(), "registration_params.json").toFile().exists()? 
						new FileInputStream(Paths.get(xnumAntnXnumFldrProp.get(), "registration_params.json").toString()):
						null;
				
				String jsonTxt = is != null? IOUtils.toString(is, "UTF-8"): null;
				JSONObject imgRegParamJsonObj = jsonTxt != null? new JSONObject(jsonTxt): null;   
				
				int xnumAnnotImgRegParamSrcImgWidth = imgRegParamJsonObj == null? 1: (int)(0.5+imgRegParamJsonObj.getInt("xnumAnnotImgRegParamSrcImgWidth"));
				int xnumAnnotImgRegParamSrcImgHeight = imgRegParamJsonObj == null? 1: (int)(0.5+imgRegParamJsonObj.getInt("xnumAnnotImgRegParamSrcImgHeight"));
				boolean xnumAnnotImgRegParamFlipHori = imgRegParamJsonObj == null? false: imgRegParamJsonObj.getBoolean("xnumAnnotImgRegParamFlipHori");
				boolean xnumAnnotImgRegParamFlipVert = imgRegParamJsonObj == null? false: imgRegParamJsonObj.getBoolean("xnumAnnotImgRegParamFlipVert");
				double xnumAnnotImgRegParamDapiImgPxlSize = imgRegParamJsonObj == null? 1: imgRegParamJsonObj.getDouble("xnumAnnotImgRegParamDapiImgPxlSize");
				String xnumAnnotImgRegParamRotation = imgRegParamJsonObj == null? null: imgRegParamJsonObj.getString("xnumAnnotImgRegParamRotation");
				double[] xnumAnnotImgRegParamSiftMatrix = imgRegParamJsonObj == null? null: IntStream.range(0, 6).mapToDouble(i -> imgRegParamJsonObj.getJSONArray("xnumAnnotImgRegParamSiftMatrix").getDouble(i)).toArray();
				double xnumAnnotImgRegParamSourceScale = imgRegParamJsonObj == null? 1: imgRegParamJsonObj.getDouble("xnumAnnotImgRegParamSourceScale");
				double xnumAnnotImgRegParamTargetScale = imgRegParamJsonObj == null? 1: imgRegParamJsonObj.getDouble("xnumAnnotImgRegParamTargetScale");
				
				// Load nonlinear transformation
				
				String transf_file = !params.getBooleanParameterValue("AffineTransformOnly")? Paths.get(xnumAntnXnumFldrProp.get(), "direct_transf.txt").toString(): null;
				
				int bspline_intervals = transf_file != null? numberOfIntervalsOfTransformation(transf_file): 0;
				double [][]bspline_cx = new double[ bspline_intervals+3 ][ bspline_intervals+3 ];
				double [][]bspline_cy = new double[ bspline_intervals+3 ][ bspline_intervals+3 ];
				if (transf_file != null) loadTransformation( transf_file, bspline_cx, bspline_cy );
				
				// Compute the deformation
				// Set these coefficients to an interpolator
				BSplineModel bspline_swx = transf_file != null? new BSplineModel(bspline_cx): null;
				BSplineModel bspline_swy = transf_file != null? new BSplineModel(bspline_cy): null;
				
	            /*
	             * Generate cell masks with their labels
	             */
				
				List<PathObject> selectedAnnotationPathObjectList = hierarchy
						.getSelectionModel()
						.getSelectedObjects()
						.stream()
						.filter(e -> e.isAnnotation() && e.hasChildObjects())
						.collect(Collectors.toList());
				
				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
				
				int maskDownsampling = params.getIntParameterValue("maskDownsampling");;
				int maskWidth = (int)Math.round(server.getWidth()/maskDownsampling);
				int maskHeight = (int)Math.round(server.getHeight()/maskDownsampling);	
				
				BufferedImage annotPathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
				List<PathObject> annotPathObjectList = new ArrayList<PathObject>();						
				
				Graphics2D annotPathObjectG2D = annotPathObjectImageMask.createGraphics();				
				annotPathObjectG2D.setBackground(new Color(0, 0, 0));
				annotPathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
				
				annotPathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
				annotPathObjectG2D.scale(1.0/maskDownsampling, 1.0/maskDownsampling);					    
				
				BufferedImage pathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
				List<PathObject> pathObjectList = new ArrayList<PathObject>();						
				
				Graphics2D pathObjectG2D = pathObjectImageMask.createGraphics();				
				pathObjectG2D.setBackground(new Color(0, 0, 0));
				pathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
				
				pathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
				pathObjectG2D.scale(1.0/maskDownsampling, 1.0/maskDownsampling);
				
				try {
					int annotPathObjectCount = 1;
					int pathObjectCount = 1;
					
					for(PathObject p: selectedAnnotationPathObjectList) {
						annotPathObjectList.add(p);
					    
					    int pb0 = (annotPathObjectCount & 0xff) >> 0; // b
					    int pb1 = (annotPathObjectCount & 0xff00) >> 8; // g
					    int pb2 = (annotPathObjectCount & 0xff0000) >> 16; // r
					    Color pMaskColor = new Color(pb2, pb1, pb0); // r, g, b
				    
					    ROI pRoi = p.getROI();
						Shape pShape = pRoi.getShape();
						
						annotPathObjectG2D.setColor(pMaskColor);
						annotPathObjectG2D.fill(pShape);
						
						annotPathObjectCount ++;
					    if(annotPathObjectCount == 0xffffff) {
					    	throw new Exception("annotation count overflow!");
					    }
						
						for(PathObject c: p.getChildObjects()) {
							pathObjectList.add(c);
						    
						    int b0 = (pathObjectCount & 0xff) >> 0; // b
						    int b1 = (pathObjectCount & 0xff00) >> 8; // g
						    int b2 = (pathObjectCount & 0xff0000) >> 16; // r
						    Color maskColor = new Color(b2, b1, b0); // r, g, b
					    
						    ROI roi = c.getROI();
							Shape shape = roi.getShape();
							
							pathObjectG2D.setColor(maskColor);
							pathObjectG2D.fill(shape);
							
							pathObjectCount ++;
						    if(pathObjectCount == 0xffffff) {
						    	throw new Exception("Cell count overflow!");
						    }
						}
					}	
				}
				catch(Exception e) {
					throw e;
				}
				finally {
					annotPathObjectG2D.dispose();	
					pathObjectG2D.dispose();	
				}
				
	            /*
	             * Read single cell data
	             * "cell_id","x_centroid","y_centroid","transcript_counts","control_probe_counts","control_codeword_counts","total_counts","cell_area","nucleus_area"
	             */

				if(xnumAntnXnumFldrProp.get().isBlank()) throw new Exception("singleCellFile is blank");

				Map<String, String> cellToSCLabelHashMap = new HashMap<>();
		        java.nio.file.Path clusterCsv = Paths.get(xnumAntnXnumFldrProp.get(), "analysis", "clustering", "gene_expression_graphclust", "clusters.csv");
		        java.nio.file.Path analysisTar = Paths.get(xnumAntnXnumFldrProp.get(), "analysis.tar.gz");
		        
		        if (Files.exists(clusterCsv)) {
		            // Read clusters from extracted CSV
		            try (BufferedReader reader = Files.newBufferedReader(clusterCsv, StandardCharsets.UTF_8)) {
		                reader.readLine();
		                String line;
		                while ((line = reader.readLine()) != null) {
		                    String[] fields = line.split(",");
		                    if (fields.length < 2) continue;
		                    String cellId = fields[0].replace("\"", "");
		                    String clusterId = fields[1].replace("\"", "");
		                    cellToSCLabelHashMap.put(cellId, clusterId);
		                }
		            }
		        } else if (Files.exists(analysisTar)) {
		            // Read clusters.csv from within analysis.tar.gz
		            try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(Files.newInputStream(analysisTar)))) {
		                TarArchiveEntry entry;
		                while ((entry = tar.getNextTarEntry()) != null) {
		                    if (!entry.isDirectory() && entry.getName().endsWith("/clusters.csv")) {
		                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(tar, StandardCharsets.UTF_8))) {
		                            reader.readLine(); // skip header
		                            String line;
		                            while ((line = reader.readLine()) != null) {
		                                String[] fields = line.split(",");
		                                if (fields.length < 2) continue;
		                                // Remove quotes if present
		                                String cellId = fields[0].replace("\"", "");
		                                String clusterId = fields[1].replace("\"", "");
		                                cellToSCLabelHashMap.put(cellId, clusterId);
		                            }
		                        }
		                        break; // stop after reading the target file
		                    }
		                }
		            }
		        } else {
		            // Clustering/analysis data not found; not critical, so just continue without cluster classes
		        	logger.warn("Clustering/analysis data not found; not critical, so just continue without cluster classes");
		        }
				
		        HashMap<String, PathObject> cellToPathObjHashMap = new HashMap<>();
		        
		        java.nio.file.Path cellsCsvGz = Paths.get(xnumAntnXnumFldrProp.get(), "cells.csv.gz");
		        java.nio.file.Path cellsParquet = Paths.get(xnumAntnXnumFldrProp.get(), "cells.parquet");
		        if (Files.exists(cellsCsvGz)) {
		        	BufferedReader singleCellGzipReader = 
	        			new BufferedReader(
        					new InputStreamReader(
    							new GZIPInputStream(Files.newInputStream(cellsCsvGz)), 
    							StandardCharsets.UTF_8
        					)
	        			);
			        
					singleCellGzipReader.readLine();
					String singleCellNextRecord;
					
			        while ((singleCellNextRecord = singleCellGzipReader.readLine()) != null) {
			        	String[] singleCellNextRecordArray = singleCellNextRecord.split(",");
			        	String cellId = singleCellNextRecordArray[0].replaceAll("\"", "");
			        	
			        	double transcriptCounts = Double.parseDouble(singleCellNextRecordArray[3]);
			        	double controlProbeCounts = Double.parseDouble(singleCellNextRecordArray[4]);
			        	double controlCodewordCounts = Double.parseDouble(singleCellNextRecordArray[5]);
			        	double totalCounts = Double.parseDouble(singleCellNextRecordArray[6]);
			        	double cellArea = Double.parseDouble(singleCellNextRecordArray[7]);
			        	double nucleusArea = Double.parseDouble(singleCellNextRecordArray[8]);
			        	
			        	double cx = Double.parseDouble(singleCellNextRecordArray[1]);
			        	double cy = Double.parseDouble(singleCellNextRecordArray[2]);
			        	
			        	double dx = cx/xnumAnnotImgRegParamDapiImgPxlSize;
			        	double dy = cy/xnumAnnotImgRegParamDapiImgPxlSize;
				        	
			        	if(xnumAnnotImgRegParamFlipVert) {
							dy = xnumAnnotImgRegParamSrcImgHeight - dy;
						}
						
						if(xnumAnnotImgRegParamFlipHori) {
							dx = xnumAnnotImgRegParamSrcImgWidth - dx;
						}
	
						if(xnumAnnotImgRegParamRotation.equals("-90") || xnumAnnotImgRegParamRotation.equals("270")) {
							double x1 = dx;
							dx = dy;
							dy = xnumAnnotImgRegParamSrcImgWidth - x1;
						}
						else if(xnumAnnotImgRegParamRotation.equals("-180") || xnumAnnotImgRegParamRotation.equals("180")) {
							dx = xnumAnnotImgRegParamSrcImgWidth - dx;
							dy = xnumAnnotImgRegParamSrcImgHeight - dy;
						}
						else if(xnumAnnotImgRegParamRotation.equals("-270") || xnumAnnotImgRegParamRotation.equals("90")) {
							double x1 = dx;
							dx = xnumAnnotImgRegParamSrcImgHeight - dy;
							dy = x1;	
						}
						
						dx /= xnumAnnotImgRegParamSourceScale;
						dy /= xnumAnnotImgRegParamSourceScale;						
						
			        	int bx = 0;
			        	int by = 0;
			        	
			        	if(params.getBooleanParameterValue("dontTransform")) {
			        		bx = (int)Math.round(dx);
			        		by = (int)Math.round(dy);
			        	}
			        	else {
				        	double ax = xnumAnnotImgRegParamSiftMatrix[0] * dx + xnumAnnotImgRegParamSiftMatrix[1] * dy + xnumAnnotImgRegParamSiftMatrix[2];
				        	double ay = xnumAnnotImgRegParamSiftMatrix[3] * dx + xnumAnnotImgRegParamSiftMatrix[4] * dy + xnumAnnotImgRegParamSiftMatrix[5];
							
				        	if(!params.getBooleanParameterValue("AffineTransformOnly")) {
					        	int bv = (int)Math.round(ay);
					        	int bu = (int)Math.round(ax);
					        	
					        	double x1 = (double)(bu * bspline_intervals) / (double)(((int)((double)server.getWidth()/xnumAnnotImgRegParamTargetScale)+0.5) - 1) + 1.0F;
								double y1 = (double)(bv * bspline_intervals) / (double)(((int)((double)server.getHeight()/xnumAnnotImgRegParamTargetScale)+0.5) - 1) + 1.0F;
								
								bspline_swx.prepareForInterpolation(x1, y1, false);
								double bspline_x_bv_bu = bspline_swx.interpolateI();
					        	
								bspline_swy.prepareForInterpolation(x1, y1, false);
								double bspline_y_bv_bu = bspline_swy.interpolateI();
								
								 bx = (int)Math.round(bspline_x_bv_bu);
								 by = (int)Math.round(bspline_y_bv_bu);
				        	}
				        	else {
					        	bx = (int)Math.round(ax);
				        		by = (int)Math.round(ay);
				        	}
			        	}
			        	
						bx *= xnumAnnotImgRegParamTargetScale;
						by *= xnumAnnotImgRegParamTargetScale;
						
			        	int fx = (int)Math.round(bx / maskDownsampling);
			        	int fy = (int)Math.round(by / maskDownsampling);
			        	
			        	if(fx < 0 || fx >= pathObjectImageMask.getWidth() || fy < 0 || fy >=  pathObjectImageMask.getHeight()) continue;
			        	
			        	int v = pathObjectImageMask.getRGB(fx, fy);
			        	int d0 = v&0xff;
			        	int d1 = (v>>8)&0xff;
			        	int d2 = (v>>16)&0xff;
						int r = d2*0x10000+d1*0x100+d0;
					    
			        	if(r == 0) continue; // This location doesn't have a cell.
				        	
			        	int pathObjectId = r - 1;  // pathObjectId starts at 1, since 0 means background
				        	
			        	PathObject cellPathObject = pathObjectList.get(pathObjectId);
			        	cellToPathObjHashMap.put(cellId, cellPathObject);
			        	
			        	String scLabelId = cellToSCLabelHashMap.get(cellId);
			        	
			        	if(scLabelId != null) {
			        		PathClass pathCls = PathClass.fromString(scLabelId);
				        	cellPathObject.setPathClass(pathCls);
			        	}
			        	
			        	double roiX = cellPathObject.getROI().getCentroidX();
			        	double roiY = cellPathObject.getROI().getCentroidY();
			        	double newDist = (new Point2D(bx, by).distance(roiX, roiY))*xnumAnnotImgRegParamDapiImgPxlSize;
			        	MeasurementList pathObjMeasList = cellPathObject.getMeasurementList();
			        	
			        	if(pathObjMeasList.containsKey("xenium:cell:cell_id")) {
			        		double minDist = pathObjMeasList.get("xenium:cell:displacement");
			        		if(newDist < minDist) {
			        			cellPathObject.setName(cellId);
			        			pathObjMeasList.put("xenium:cell:displacement", newDist);
			        			pathObjMeasList.put("xenium:cell:x_centroid", cx);
			        			pathObjMeasList.put("xenium:cell:y_centroid", cy);
			        			pathObjMeasList.put("xenium:cell:transcript_counts", transcriptCounts);
			        			pathObjMeasList.put("xenium:cell:control_probe_counts", controlProbeCounts);
			        			pathObjMeasList.put("xenium:cell:control_codeword_counts", controlCodewordCounts);
			        			pathObjMeasList.put("xenium:cell:total_counts", totalCounts);
			        			pathObjMeasList.put("xenium:cell:cell_area", cellArea);
			        			pathObjMeasList.put("xenium:cell:nucleus_area", nucleusArea);
			        		}
			        	}
			        	else {
			        		cellPathObject.setName(cellId);
		        			pathObjMeasList.put("xenium:cell:displacement", newDist);
		        			pathObjMeasList.put("xenium:cell:x_centroid", cx);
		        			pathObjMeasList.put("xenium:cell:y_centroid", cy);
		        			pathObjMeasList.put("xenium:cell:transcript_counts", transcriptCounts);
		        			pathObjMeasList.put("xenium:cell:control_probe_counts", controlProbeCounts);
		        			pathObjMeasList.put("xenium:cell:control_codeword_counts", controlCodewordCounts);
		        			pathObjMeasList.put("xenium:cell:total_counts", totalCounts);
		        			pathObjMeasList.put("xenium:cell:cell_area", cellArea);
		        			pathObjMeasList.put("xenium:cell:nucleus_area", nucleusArea);     		        
			        	}
			        	
			        	pathObjMeasList.close(); 
		        	}		        	
		        	
			        singleCellGzipReader.close();
		        }
		        else if (Files.exists(cellsParquet)) {
		        	// Read from Parquet using AvroParquetReader
		        	try (ParquetReader<GenericRecord> parquetReader = AvroParquetReader.<GenericRecord>builder(HadoopInputFile.fromPath(new Path(cellsParquet.toString()), new Configuration())).build()) {
		        		GenericRecord record;
		        		while ((record = parquetReader.read()) != null) {
				        	String cellId = record.get("cell_id").toString();
				        	
				        	double transcriptCounts = ((Number) record.get("transcript_counts")).doubleValue();
				        	double controlProbeCounts = ((Number) record.get("control_probe_counts")).doubleValue();
				        	double controlCodewordCounts = ((Number) record.get("control_codeword_counts")).doubleValue();
				        	double totalCounts = ((Number) record.get("total_counts")).doubleValue();
				        	double cellArea = ((Number) record.get("cell_area")).doubleValue();
				        	double nucleusArea = ((Number) record.get("nucleus_area")).doubleValue();
				        	
				        	double cx = ((Number) record.get("x_centroid")).doubleValue();
				        	double cy = ((Number) record.get("y_centroid")).doubleValue();
				        	
				        	double dx = cx/xnumAnnotImgRegParamDapiImgPxlSize;
				        	double dy = cy/xnumAnnotImgRegParamDapiImgPxlSize;
					        	
				        	if(xnumAnnotImgRegParamFlipVert) {
								dy = xnumAnnotImgRegParamSrcImgHeight - dy;
							}
							
							if(xnumAnnotImgRegParamFlipHori) {
								dx = xnumAnnotImgRegParamSrcImgWidth - dx;
							}
		
							if(xnumAnnotImgRegParamRotation.equals("-90") || xnumAnnotImgRegParamRotation.equals("270")) {
								double x1 = dx;
								dx = dy;
								dy = xnumAnnotImgRegParamSrcImgWidth - x1;
							}
							else if(xnumAnnotImgRegParamRotation.equals("-180") || xnumAnnotImgRegParamRotation.equals("180")) {
								dx = xnumAnnotImgRegParamSrcImgWidth - dx;
								dy = xnumAnnotImgRegParamSrcImgHeight - dy;
							}
							else if(xnumAnnotImgRegParamRotation.equals("-270") || xnumAnnotImgRegParamRotation.equals("90")) {
								double x1 = dx;
								dx = xnumAnnotImgRegParamSrcImgHeight - dy;
								dy = x1;	
							}
							
							dx /= xnumAnnotImgRegParamSourceScale;
							dy /= xnumAnnotImgRegParamSourceScale;						
							
				        	int bx = 0;
				        	int by = 0;
				        	
				        	if(params.getBooleanParameterValue("dontTransform")) {
				        		bx = (int)Math.round(dx);
				        		by = (int)Math.round(dy);
				        	}
				        	else {
					        	double ax = xnumAnnotImgRegParamSiftMatrix[0] * dx + xnumAnnotImgRegParamSiftMatrix[1] * dy + xnumAnnotImgRegParamSiftMatrix[2];
					        	double ay = xnumAnnotImgRegParamSiftMatrix[3] * dx + xnumAnnotImgRegParamSiftMatrix[4] * dy + xnumAnnotImgRegParamSiftMatrix[5];
								
					        	if(!params.getBooleanParameterValue("AffineTransformOnly")) {
						        	int bv = (int)Math.round(ay);
						        	int bu = (int)Math.round(ax);
						        	
						        	double x1 = (double)(bu * bspline_intervals) / (double)(((int)((double)server.getWidth()/xnumAnnotImgRegParamTargetScale)+0.5) - 1) + 1.0F;
									double y1 = (double)(bv * bspline_intervals) / (double)(((int)((double)server.getHeight()/xnumAnnotImgRegParamTargetScale)+0.5) - 1) + 1.0F;
									
									bspline_swx.prepareForInterpolation(x1, y1, false);
									double bspline_x_bv_bu = bspline_swx.interpolateI();
						        	
									bspline_swy.prepareForInterpolation(x1, y1, false);
									double bspline_y_bv_bu = bspline_swy.interpolateI();
									
									 bx = (int)Math.round(bspline_x_bv_bu);
									 by = (int)Math.round(bspline_y_bv_bu);
					        	}
					        	else {
						        	bx = (int)Math.round(ax);
					        		by = (int)Math.round(ay);
					        	}
				        	}
				        	
							bx *= xnumAnnotImgRegParamTargetScale;
							by *= xnumAnnotImgRegParamTargetScale;
							
				        	int fx = (int)Math.round(bx / maskDownsampling);
				        	int fy = (int)Math.round(by / maskDownsampling);
				        	
				        	if(fx < 0 || fx >= pathObjectImageMask.getWidth() || fy < 0 || fy >=  pathObjectImageMask.getHeight()) continue;
				        	
				        	int v = pathObjectImageMask.getRGB(fx, fy);
				        	int d0 = v&0xff;
				        	int d1 = (v>>8)&0xff;
				        	int d2 = (v>>16)&0xff;
							int r = d2*0x10000+d1*0x100+d0;
						    
				        	if(r == 0) continue; // This location doesn't have a cell.
					        	
				        	int pathObjectId = r - 1;  // pathObjectId starts at 1, since 0 means background
					        	
				        	PathObject cellPathObject = pathObjectList.get(pathObjectId);
				        	cellToPathObjHashMap.put(cellId, cellPathObject);
				        	
				        	String scLabelId = cellToSCLabelHashMap.get(cellId);
				        	
				        	if(scLabelId != null) {
				        		PathClass pathCls = PathClass.fromString(scLabelId);
					        	cellPathObject.setPathClass(pathCls);
				        	}
				        	
				        	double roiX = cellPathObject.getROI().getCentroidX();
				        	double roiY = cellPathObject.getROI().getCentroidY();
				        	double newDist = (new Point2D(bx, by).distance(roiX, roiY))*xnumAnnotImgRegParamDapiImgPxlSize;
				        	MeasurementList pathObjMeasList = cellPathObject.getMeasurementList();
				        	
				        	if(pathObjMeasList.containsKey("xenium:cell:cell_id")) {
				        		double minDist = pathObjMeasList.get("xenium:cell:displacement");
				        		if(newDist < minDist) {
				        			cellPathObject.setName(cellId);
				        			pathObjMeasList.put("xenium:cell:displacement", newDist);
				        			pathObjMeasList.put("xenium:cell:x_centroid", cx);
				        			pathObjMeasList.put("xenium:cell:y_centroid", cy);
				        			pathObjMeasList.put("xenium:cell:transcript_counts", transcriptCounts);
				        			pathObjMeasList.put("xenium:cell:control_probe_counts", controlProbeCounts);
				        			pathObjMeasList.put("xenium:cell:control_codeword_counts", controlCodewordCounts);
				        			pathObjMeasList.put("xenium:cell:total_counts", totalCounts);
				        			pathObjMeasList.put("xenium:cell:cell_area", cellArea);
				        			pathObjMeasList.put("xenium:cell:nucleus_area", nucleusArea);
				        		}
				        	}
				        	else {
				        		cellPathObject.setName(cellId);
			        			pathObjMeasList.put("xenium:cell:displacement", newDist);
			        			pathObjMeasList.put("xenium:cell:x_centroid", cx);
			        			pathObjMeasList.put("xenium:cell:y_centroid", cy);
			        			pathObjMeasList.put("xenium:cell:transcript_counts", transcriptCounts);
			        			pathObjMeasList.put("xenium:cell:control_probe_counts", controlProbeCounts);
			        			pathObjMeasList.put("xenium:cell:control_codeword_counts", controlCodewordCounts);
			        			pathObjMeasList.put("xenium:cell:total_counts", totalCounts);
			        			pathObjMeasList.put("xenium:cell:cell_area", cellArea);
			        			pathObjMeasList.put("xenium:cell:nucleus_area", nucleusArea);     		        
				        	}
				        	
				        	pathObjMeasList.close(); 
			        	}
		        	}
		        }
		        
				/*
	             * Read feature matrix data
	             */
			
				java.nio.file.Path cellFeatureMatrixTarGzFilePath  = Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix.tar.gz");
		        java.nio.file.Path barcodeTsv  = Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix", "barcodes.tsv.gz");
		        java.nio.file.Path featureTsv  = Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix", "features.tsv.gz");
		        java.nio.file.Path matrixMtx   = Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix", "matrix.mtx.gz");
		        
		        if (Files.exists(barcodeTsv) && Files.exists(featureTsv) && Files.exists(matrixMtx)) {
					List<String> barcodeList = new ArrayList<>();
					
			        try (BufferedReader barcodeGzipReader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(barcodeTsv)), StandardCharsets.UTF_8))) {	
						String barcodeNextRecord;
						while ((barcodeNextRecord = barcodeGzipReader.readLine()) != null) {
							barcodeList.add(barcodeNextRecord.trim());
						}
			        }

					List<String> featureIdList = new ArrayList<>();
					List<String> featureNameList = new ArrayList<>();
					List<String> featureTypeList = new ArrayList<>();

					try (BufferedReader featureGzipReader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(featureTsv)), StandardCharsets.UTF_8))) {
		                String featureNextRecord;
						while ((featureNextRecord = featureGzipReader.readLine()) != null) {
							String[] featureNextRecordArray = featureNextRecord.split("\t");
							featureIdList.add(featureNextRecordArray[0]);
							featureNameList.add(featureNextRecordArray[1]);
							featureTypeList.add(featureNextRecordArray[2]);
						}
					}
					
					int[][] matrix = new int[featureNameList.size()][barcodeList.size()];
					
					try (BufferedReader matrixGzipReader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(matrixMtx)), StandardCharsets.UTF_8))) {
						matrixGzipReader.readLine();
						matrixGzipReader.readLine();
						matrixGzipReader.readLine();
						
						String matrixNextRecord;
						while ((matrixNextRecord = matrixGzipReader.readLine()) != null) {
							String[] matrixNextRecordArray = matrixNextRecord.split(" ");
							int f = Integer.parseInt(matrixNextRecordArray[0])-1;
							int b = Integer.parseInt(matrixNextRecordArray[1])-1;
							int v = Integer.parseInt(matrixNextRecordArray[2]);
							
							matrix[f][b] = v;
						}
					}
						
					IntStream.range(0, barcodeList.size()).parallel().forEach(b -> {
//					for(int b = 0; b < barcodeList.size(); b ++) {
						if(cellToPathObjHashMap.containsKey(barcodeList.get(b))) {
					    	PathObject c = cellToPathObjHashMap.get(barcodeList.get(b));
					    	MeasurementList pathObjMeasList = c.getMeasurementList();
					    	
					    	for(int f = 0; f < featureNameList.size(); f ++) {	
					    		if(!params.getBooleanParameterValue("inclBlankCodeword") && featureTypeList.get(f).compareTo("Blank Codeword")==0) continue;
					    		if(!params.getBooleanParameterValue("inclUnassignedCodeword") && featureTypeList.get(f).compareTo("Unassigned Codeword")==0) continue;
					    		if(!params.getBooleanParameterValue("inclDeprecatedCodeword") && featureTypeList.get(f).compareTo("Deprecated Codeword")==0) continue;
					    		if(!params.getBooleanParameterValue("inclIntergenicRegion") && featureTypeList.get(f).compareTo("Genomic Control")==0) continue;
								if(!params.getBooleanParameterValue("inclGeneExpr") && featureTypeList.get(f).compareTo("Gene Expression")==0) continue;
								if(!params.getBooleanParameterValue("inclNegCtrlCodeword") && featureTypeList.get(f).compareTo("Negative Control Codeword")==0) continue;
								if(!params.getBooleanParameterValue("inclNegCtrlProbe") && featureTypeList.get(f).compareTo("Negative Control Probe")==0) continue;
					    		
								pathObjMeasList.put("transcript:"+featureNameList.get(f), matrix[f][b]);  
					    			 
					    	}
					    	
					    	pathObjMeasList.close();
						}
					});
//					}
		        } else if(Files.exists(cellFeatureMatrixTarGzFilePath)) {
					List<String> barcodeList = new ArrayList<>();
					List<String> featureIdList = new ArrayList<>();
					List<String> featureNameList = new ArrayList<>();
					List<String> featureTypeList = new ArrayList<>();		   
					
		            // -------- PASS 1: read barcodes.tsv.gz and features.tsv.gz --------
		            try (FileInputStream fis = new FileInputStream(cellFeatureMatrixTarGzFilePath.toFile());
		                 GZIPInputStream gzipIn = new GZIPInputStream(fis);
		                 TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {

		                TarArchiveEntry entry;
		                while ((entry = tarIn.getNextTarEntry()) != null) {
		                    if (entry.isDirectory()) continue;
		                    final String name = entry.getName();

		                    if (name.endsWith("barcodes.tsv.gz")) {
		                    	BoundedInputStream bounded = new BoundedInputStream(tarIn, entry.getSize());
		                    	bounded.setPropagateClose(false);
		                    	
		                    	try (bounded; 
		                    		 GZIPInputStream gz = new GZIPInputStream(bounded);
		                    		 BufferedReader br = new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8))) {
		                    		
		                    		String line;
		                            while ((line = br.readLine()) != null) {
		                                barcodeList.add(line.trim());
		                            }
		                    	}
		                        
		                    } else if (name.endsWith("features.tsv.gz")) {
		                    	BoundedInputStream bounded = new BoundedInputStream(tarIn, entry.getSize());
		                    	bounded.setPropagateClose(false);
		                    	
		                    	try (bounded; 
		                    		 GZIPInputStream gz = new GZIPInputStream(bounded);
		                    		 BufferedReader br = new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8))) {

			                            String line;
			                            while ((line = br.readLine()) != null) {
			                                // feature_id \t feature_name \t feature_type
			                                String[] arr = line.split("\t", -1);
			                                // guard for short rows
			                                String id   = arr.length > 0 ? arr[0] : "";
			                                String name2 = arr.length > 1 ? arr[1] : "";
			                                String type = arr.length > 2 ? arr[2] : "";
			                                featureIdList.add(id);
			                                featureNameList.add(name2);
			                                featureTypeList.add(type);
			                            }
			                        }
		                    }
		                    // keep scanning; tarIn is still open because of CloseShieldInputStream
		                }
		            }

		            // allocate matrix after we know sizes
		            int[][] matrix = new int[featureNameList.size()][barcodeList.size()];

		            // -------- PASS 2: read matrix.mtx.gz and fill matrix --------
		            try (FileInputStream fis = new FileInputStream(cellFeatureMatrixTarGzFilePath.toFile());
		                 GZIPInputStream gzipIn = new GZIPInputStream(fis);
		                 TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {

		                TarArchiveEntry entry;
		                while ((entry = tarIn.getNextTarEntry()) != null) {
		                    if (entry.isDirectory()) continue;
		                    final String name = entry.getName();

		                    if (name.endsWith("matrix.mtx.gz")) {
		                    	BoundedInputStream bounded = new BoundedInputStream(tarIn, entry.getSize());
		                    	bounded.setPropagateClose(false);
		                    	
		                    	try (bounded; 
		                    		 GZIPInputStream gz = new GZIPInputStream(bounded);
		                    		 BufferedReader br = new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8))) {

		                            // MTX header: skip comments starting with '%'
		                            String line;
		                            while ((line = br.readLine()) != null && line.startsWith("%")) {
		                                // skip header/comment lines
		                            }
		                            // The first non-comment line is usually "nRows nCols nNonZero"
		                            // We don't strictly need it because we already allocated
		                            // by features x barcodes, but we consumed it to align the reader.

		                            // Read triplets: row col value (1-based indices)
		                            while ((line = br.readLine()) != null) {
		                                line = line.trim();
		                                if (line.isEmpty()) continue;
		                                String[] arr = line.split("\\s+");
		                                if (arr.length < 3) continue;
		                                int f = Integer.parseInt(arr[0]) - 1; // feature index
		                                int b = Integer.parseInt(arr[1]) - 1; // barcode index
		                                int v = Integer.parseInt(arr[2]);
		                                if (f >= 0 && f < matrix.length && b >= 0 && b < matrix[0].length) {
		                                    matrix[f][b] = v;
		                                }
		                            }
		                        }
		                    }
		                }
		            }
		            

		            IntStream.range(0, barcodeList.size()).parallel().forEach(b -> {
//					for(int b = 0; b < barcodeList.size(); b ++) {
						if(cellToPathObjHashMap.containsKey(barcodeList.get(b))) {
					    	PathObject c = cellToPathObjHashMap.get(barcodeList.get(b));
					    	MeasurementList pathObjMeasList = c.getMeasurementList();
					    	
					    	for(int f = 0; f < featureNameList.size(); f ++) {	
					    		if(!params.getBooleanParameterValue("inclBlankCodeword") && featureTypeList.get(f).compareTo("Blank Codeword")==0) continue;
					    		if(!params.getBooleanParameterValue("inclUnassignedCodeword") && featureTypeList.get(f).compareTo("Unassigned Codeword")==0) continue;
					    		if(!params.getBooleanParameterValue("inclDeprecatedCodeword") && featureTypeList.get(f).compareTo("Deprecated Codeword")==0) continue;
					    		if(!params.getBooleanParameterValue("inclIntergenicRegion") && featureTypeList.get(f).compareTo("Genomic Control")==0) continue;
								if(!params.getBooleanParameterValue("inclGeneExpr") && featureTypeList.get(f).compareTo("Gene Expression")==0) continue;
								if(!params.getBooleanParameterValue("inclNegCtrlCodeword") && featureTypeList.get(f).compareTo("Negative Control Codeword")==0) continue;
								if(!params.getBooleanParameterValue("inclNegCtrlProbe") && featureTypeList.get(f).compareTo("Negative Control Probe")==0) continue;
					    		
								pathObjMeasList.put("transcript:"+featureNameList.get(f), matrix[f][b]);  
					    			 
					    	}
					    	
					    	pathObjMeasList.close();
						}
					});
//					}
		        }
				
				if(params.getBooleanParameterValue("removeUnlabeledCells")) {
					for(PathObject c: pathObjectList) {
						if(c.getPathClass() == null) {
							c.getParent().removeChildObject(c);
						}
					}
				}

		        hierarchy.getSelectionModel().setSelectedObject(null);
			}
			catch(Exception e) {	
//				Dialogs.showErrorMessage("Error", e.getMessage());
				
				lastResults =  "Something went wrong: "+e.getMessage();
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
	protected void preprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
		if(params.getStringParameterValue("xeniumDir").isBlank()) {

			File xnumDir = FileChoosers.promptForDirectory("Xenium directory", new File(xnumAntnXnumFldrProp.get()));
			
			if (xnumDir != null) {
				xnumAntnXnumFldrProp.set(xnumDir.toString());
			} else {
//				Dialogs.showErrorMessage("Warning", "No Xenium directory is selected!");
				lastResults =  "No Xenium directory is selected!";
				logger.warn(lastResults);
			}
		} else {
			xnumAntnXnumFldrProp.set(params.getStringParameterValue("xeniumDir"));
		}
	};
	
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
		List<Class<? extends PathObject>> list = new ArrayList<>();
		list.add(TMACoreObject.class);
		list.add(PathAnnotationObject.class);
		list.add(PathRootObject.class);
		return list;		

//		return Arrays.asList(
//				PathAnnotationObject.class,
//				TMACoreObject.class
//				);	
	}

}