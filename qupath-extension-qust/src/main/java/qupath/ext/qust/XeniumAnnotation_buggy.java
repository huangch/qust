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
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
import qupath.fx.dialogs.Dialogs;
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
import qupath.lib.gui.QuPathGUI;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.io.IOUtils;
import org.apache.commons.compress.archivers.tar.*;

import org.json.JSONObject;

import hdf.hdf5lib.H5;
import hdf.hdf5lib.HDF5Constants;

/**
 * Plugin for loading 10x Visium Annotation 
 * 
 * @author Chao Hui Huang
 *
 */
public class XeniumAnnotation_buggy extends AbstractDetectionPlugin<BufferedImage> {
	
	private static Logger logger = LoggerFactory.getLogger(XeniumAnnotation_buggy.class);
	
	private StringProperty xnumAntnXnumFldrProp = PathPrefs.createPersistentPreference("xnumAntnXnumFldr", ""); 
	
	private ParameterList params = null;

	private String lastResults = null;
	
	/**
	 * Constructor.
	 */
	public XeniumAnnotation_buggy() {	
		params = new ParameterList()
			.addTitleParameter("10X Xenium Data Loader")
			.addEmptyParameter("Required files:")
			.addEmptyParameter("    analysis.tar.gz")
			.addEmptyParameter("    cells.parquet")
			.addEmptyParameter("    cell_feature_matrix.h5")
			.addStringParameter("xeniumDir", "Xenium directory (activating an open folder dialog by leaving this blank)", xnumAntnXnumFldrProp.get(), "Xenium Out Directory")
			.addEmptyParameter("")
			.addBooleanParameter("dontTransform", "DO NOT transform? (default: false)", false, "DO NOT transform? (default: false)")		
			.addBooleanParameter("AffineTransformOnly", "Affine (linear) transform ONLY? (default: false)", false, "Affine (linear) transform ONLY? (default: false)")		
			.addBooleanParameter("removeUnlabeledCells", "Remove unlabeled cells? (default: true)", true, "Remove unlabeled cells? (default: true)")		
//			.addBooleanParameter("inclGeneExpr", "Include Gene Expression? (default: true)", true, "Include Gene Expression? (default: true)")		
//			.addBooleanParameter("inclBlankCodeword", "Include Blank Codeword? (default: false)", false, "Include Blank Codeword? (default: false)")
//			.addBooleanParameter("inclUnassignedCodeword", "Include Unassigned Codeword? (default: false)", false, "Include Unassigned Codeword? (default: false)")
//			.addBooleanParameter("inclDeprecatedCodeword", "Include Deprecated Codeword? (default: false)", false, "Include Deprecated Codeword? (default: false)")
//			.addBooleanParameter("inclIntergenicRegion", "Include Intergenic Region? (default: false)", false, "Include Intergenic Region? (default: false)")
//			.addBooleanParameter("inclNegCtrlCodeword", "Include Negative Control Codeword? (default: false)", false, "Include Negative Control Codeword? (default: false)")		
//			.addBooleanParameter("inclNegCtrlProbe", "Include Negative Control Probe? (default: false)", false, "Include Negative Control Probe? (default: false)")		
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

		
		
		
		
		public static String[] readFixedStringDataset(long fileId, String path) throws Exception {
			long did = H5.H5Dopen(fileId, path, HDF5Constants.H5P_DEFAULT);
			long sid = H5.H5Dget_space(did);
			long[] dims = new long[1];
			H5.H5Sget_simple_extent_dims(sid, dims, null);
			int len = (int) dims[0];
			long tid = H5.H5Dget_type(did);
			long strLen = H5.H5Tget_size(tid);
			byte[][] buffer = new byte[len][(int) strLen];
			H5.H5Dread(did, tid,
					HDF5Constants.H5S_ALL, HDF5Constants.H5S_ALL,
					HDF5Constants.H5P_DEFAULT, buffer);
			String[] result = new String[len];
			for (int i = 0; i < len; i++) {
				result[i] = new String(buffer[i]).trim();
			}
			H5.H5Tclose(tid);
			H5.H5Sclose(sid);
			H5.H5Dclose(did);
			return result;
		}
		
		
		public static int[] readInt1D(long fileId, String path) throws Exception {
			long did = H5.H5Dopen(fileId, path, HDF5Constants.H5P_DEFAULT);
			long sid = H5.H5Dget_space(did);
			long[] dims = new long[1];
			H5.H5Sget_simple_extent_dims(sid, dims, null);
			int[] data = new int[(int) dims[0]];
			H5.H5Dread(did, HDF5Constants.H5T_STD_I32LE,
						HDF5Constants.H5S_ALL, HDF5Constants.H5S_ALL,
						HDF5Constants.H5P_DEFAULT, data);
			H5.H5Sclose(sid);
			H5.H5Dclose(did);
			return data;
		}
		 
//		@Override
//		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
//			ImageServer<BufferedImage> server = imageData.getServer();				
//			PathObjectHierarchy hierarchy = imageData.getHierarchy();
//			ArrayList<PathObject> resultPathObjectList = new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
//			
//			try {
//				// Load linear transformation
//				
//				InputStream is = Paths.get(xnumAntnXnumFldrProp.get(), "registration_params.json").toFile().exists()? 
//						new FileInputStream(Paths.get(xnumAntnXnumFldrProp.get(), "registration_params.json").toString()):
//						null;
//				
//				String jsonTxt = is != null? IOUtils.toString(is, "UTF-8"): null;
//				JSONObject imgRegParamJsonObj = jsonTxt != null? new JSONObject(jsonTxt): null;   
//				
//				int xnumAnnotImgRegParamSrcImgWidth = imgRegParamJsonObj == null? 1: (int)(0.5+imgRegParamJsonObj.getInt("xnumAnnotImgRegParamSrcImgWidth"));
//				int xnumAnnotImgRegParamSrcImgHeight = imgRegParamJsonObj == null? 1: (int)(0.5+imgRegParamJsonObj.getInt("xnumAnnotImgRegParamSrcImgHeight"));
//				boolean xnumAnnotImgRegParamFlipHori = imgRegParamJsonObj == null? false: imgRegParamJsonObj.getBoolean("xnumAnnotImgRegParamFlipHori");
//				boolean xnumAnnotImgRegParamFlipVert = imgRegParamJsonObj == null? false: imgRegParamJsonObj.getBoolean("xnumAnnotImgRegParamFlipVert");
//				double xnumAnnotImgRegParamDapiImgPxlSize = imgRegParamJsonObj == null? 1: imgRegParamJsonObj.getDouble("xnumAnnotImgRegParamDapiImgPxlSize");
//				String xnumAnnotImgRegParamRotation = imgRegParamJsonObj == null? null: imgRegParamJsonObj.getString("xnumAnnotImgRegParamRotation");
//				double[] xnumAnnotImgRegParamSiftMatrix = imgRegParamJsonObj == null? null: IntStream.range(0, 6).mapToDouble(i -> imgRegParamJsonObj.getJSONArray("xnumAnnotImgRegParamSiftMatrix").getDouble(i)).toArray();
//				double xnumAnnotImgRegParamSourceScale = imgRegParamJsonObj == null? 1: imgRegParamJsonObj.getDouble("xnumAnnotImgRegParamSourceScale");
//				double xnumAnnotImgRegParamTargetScale = imgRegParamJsonObj == null? 1: imgRegParamJsonObj.getDouble("xnumAnnotImgRegParamTargetScale");
//				
//				// Load nonlinear transformation
//				
//				String transf_file = !params.getBooleanParameterValue("AffineTransformOnly")? Paths.get(xnumAntnXnumFldrProp.get(), "direct_transf.txt").toString(): null;
//				
//				int bspline_intervals = transf_file != null? numberOfIntervalsOfTransformation(transf_file): 0;
//				double [][]bspline_cx = new double[ bspline_intervals+3 ][ bspline_intervals+3 ];
//				double [][]bspline_cy = new double[ bspline_intervals+3 ][ bspline_intervals+3 ];
//				if (transf_file != null) loadTransformation( transf_file, bspline_cx, bspline_cy );
//				
//				// Compute the deformation
//				// Set these coefficients to an interpolator
//				BSplineModel bspline_swx = transf_file != null? new BSplineModel(bspline_cx): null;
//				BSplineModel bspline_swy = transf_file != null? new BSplineModel(bspline_cy): null;
//				
//	            /*
//	             * Generate cell masks with their labels
//	             */
//				
//				List<PathObject> selectedAnnotationPathObjectList = hierarchy
//						.getSelectionModel()
//						.getSelectedObjects()
//						.stream()
//						.filter(e -> e.isAnnotation() && e.hasChildObjects())
//						.collect(Collectors.toList());
//				
//				if(selectedAnnotationPathObjectList.isEmpty()) throw new Exception("Missed selected annotations");
//				
//				int maskDownsampling = params.getIntParameterValue("maskDownsampling");;
//				int maskWidth = (int)Math.round(server.getWidth()/maskDownsampling);
//				int maskHeight = (int)Math.round(server.getHeight()/maskDownsampling);	
//				
//				BufferedImage annotPathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
//				List<PathObject> annotPathObjectList = new ArrayList<PathObject>();						
//				
//				Graphics2D annotPathObjectG2D = annotPathObjectImageMask.createGraphics();				
//				annotPathObjectG2D.setBackground(new Color(0, 0, 0));
//				annotPathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
//				
//				annotPathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
//				annotPathObjectG2D.scale(1.0/maskDownsampling, 1.0/maskDownsampling);					    
//				
//				BufferedImage pathObjectImageMask = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
//				List<PathObject> pathObjectList = new ArrayList<PathObject>();						
//				
//				Graphics2D pathObjectG2D = pathObjectImageMask.createGraphics();				
//				pathObjectG2D.setBackground(new Color(0, 0, 0));
//				pathObjectG2D.clearRect(0, 0, maskWidth, maskHeight);
//				
//				pathObjectG2D.setClip(0, 0, maskWidth, maskHeight);
//				pathObjectG2D.scale(1.0/maskDownsampling, 1.0/maskDownsampling);
//				
//				try {
//					int annotPathObjectCount = 1;
//					int pathObjectCount = 1;
//					
//					for(PathObject p: selectedAnnotationPathObjectList) {
//						annotPathObjectList.add(p);
//					    
//					    int pb0 = (annotPathObjectCount & 0xff) >> 0; // b
//					    int pb1 = (annotPathObjectCount & 0xff00) >> 8; // g
//					    int pb2 = (annotPathObjectCount & 0xff0000) >> 16; // r
//					    Color pMaskColor = new Color(pb2, pb1, pb0); // r, g, b
//				    
//					    ROI pRoi = p.getROI();
//						Shape pShape = pRoi.getShape();
//						
//						annotPathObjectG2D.setColor(pMaskColor);
//						annotPathObjectG2D.fill(pShape);
//						
//						annotPathObjectCount ++;
//					    if(annotPathObjectCount == 0xffffff) {
//					    	throw new Exception("annotation count overflow!");
//					    }
//						
//						for(PathObject c: p.getChildObjects()) {
//							pathObjectList.add(c);
//						    
//						    int b0 = (pathObjectCount & 0xff) >> 0; // b
//						    int b1 = (pathObjectCount & 0xff00) >> 8; // g
//						    int b2 = (pathObjectCount & 0xff0000) >> 16; // r
//						    Color maskColor = new Color(b2, b1, b0); // r, g, b
//					    
//						    ROI roi = c.getROI();
//							Shape shape = roi.getShape();
//							
//							pathObjectG2D.setColor(maskColor);
//							pathObjectG2D.fill(shape);
//							
//							pathObjectCount ++;
//						    if(pathObjectCount == 0xffffff) {
//						    	throw new Exception("Cell count overflow!");
//						    }
//						}
//					}	
//				}
//				catch(Exception e) {
//					throw e;
//				}
//				finally {
//					annotPathObjectG2D.dispose();	
//					pathObjectG2D.dispose();	
//				}
//				
//	            /*
//	             * Read single cell data
//	             * "cell_id","x_centroid","y_centroid","transcript_counts","control_probe_counts","control_codeword_counts","total_counts","cell_area","nucleus_area"
//	             */
//
//				if(xnumAntnXnumFldrProp.get().isBlank()) throw new Exception("singleCellFile is blank");
//								
//				HashMap<String, String> cellToSCLabelHashMap = new HashMap<>();
//
//				java.nio.file.Path clustersCsvFileJPath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "analysis", "clustering", "gene_expression_graphclust", "clusters.csv");
//				java.nio.file.Path analysisTarGzFileJPath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "analysis.tar.gz");
//				
//				if (java.nio.file.Files.exists(analysisTarGzFileJPath)) {
//					try (
//							FileInputStream fis = new FileInputStream(java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "analysis.tar.gz").toString());
//							GZIPInputStream gis = new GZIPInputStream(fis);
//							TarArchiveInputStream tis = new TarArchiveInputStream(gis)
//					) {
//						TarArchiveEntry entry;
//						while ((entry = tis.getNextTarEntry()) != null) {
//							if (entry.isDirectory()) continue;
//							if (entry.getName().equals("analysis/clustering/gene_expression_graphclust/clusters.csv")) {
//								BufferedReader reader = new BufferedReader(new InputStreamReader(tis, "UTF-8"));
//								reader.readLine();
//								String line;
//	
//								while ((line = reader.readLine()) != null) {
//						        	String[] scLabelNextRecordArray = line.split(",");
//						        	String cellId = scLabelNextRecordArray[0].replaceAll("\"", "");;
//						        	String scLabelId = scLabelNextRecordArray[1].replaceAll("\"", "");;
//						        	cellToSCLabelHashMap.put(cellId, scLabelId);
//								}
//								break;  // stop after reading target file
//							}
//						}
//					}
//				} else if (java.nio.file.Files.exists(clustersCsvFileJPath)) {
//					String scLabelFilePath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "analysis", "clustering", "gene_expression_graphclust", "clusters.csv").toString();
//					FileReader scLabelFileReader = new FileReader(new File(scLabelFilePath));
//					BufferedReader scLabelReader = new BufferedReader(scLabelFileReader);
//					scLabelReader.readLine();
//					String scLabelNextRecord;
//					
//					while ((scLabelNextRecord = scLabelReader.readLine()) != null) {
//			        	String[] scLabelNextRecordArray = scLabelNextRecord.split(",");
//			        	String cellId = scLabelNextRecordArray[0].replaceAll("\"", "");;
//			        	String scLabelId = scLabelNextRecordArray[1].replaceAll("\"", "");;
//			        	cellToSCLabelHashMap.put(cellId, scLabelId);
//					}
//					
//					scLabelReader.close();
//				} else {
//					throw new Exception("analysis file error");
//				}
//				
//				HashMap<String, PathObject> cellToPathObjHashMap = new HashMap<>();
//			
//				java.nio.file.Path cellsParquetFileJPath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "cells.parquet");
//				java.nio.file.Path cellsCsvGzFileJPath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "cells.csv.gz");
//				
//				if (java.nio.file.Files.exists(cellsCsvGzFileJPath)) {
//					String singleCellFilePath = cellsCsvGzFileJPath.toString();
//					GZIPInputStream singleCellGzipStream = new GZIPInputStream(new FileInputStream(singleCellFilePath));
//					BufferedReader singleCellGzipReader = new BufferedReader(new InputStreamReader(singleCellGzipStream));
//					singleCellGzipReader.readLine();
//								    
//					String singleCellNextRecord;
//			        while ((singleCellNextRecord = singleCellGzipReader.readLine()) != null) {
//			        	String[] singleCellNextRecordArray = singleCellNextRecord.split(",");
//			        	String cellId = singleCellNextRecordArray[0].replaceAll("\"", "");
//			        	
//	//		        	double transcriptCounts = Double.parseDouble(singleCellNextRecordArray[3]);
//	//		        	double controlProbeCounts = Double.parseDouble(singleCellNextRecordArray[4]);
//	//		        	double controlCodewordCounts = Double.parseDouble(singleCellNextRecordArray[5]);
//	//		        	double totalCounts = Double.parseDouble(singleCellNextRecordArray[6]);
////			        	double cellArea = Double.parseDouble(singleCellNextRecordArray[7]);
////			        	double nucleusArea = Double.parseDouble(singleCellNextRecordArray[8]);
//			        	
//			        	double cx = Double.parseDouble(singleCellNextRecordArray[1]);
//			        	double cy = Double.parseDouble(singleCellNextRecordArray[2]);
//			        	
//			        	double dx = cx/xnumAnnotImgRegParamDapiImgPxlSize;
//			        	double dy = cy/xnumAnnotImgRegParamDapiImgPxlSize;
//			        		
//			        	if(xnumAnnotImgRegParamFlipVert) {
//							dy = xnumAnnotImgRegParamSrcImgHeight - dy;
//						}
//						
//						if(xnumAnnotImgRegParamFlipHori) {
//							dx = xnumAnnotImgRegParamSrcImgWidth - dx;
//						}
//	
//						if(xnumAnnotImgRegParamRotation.equals("-90") || xnumAnnotImgRegParamRotation.equals("270")) {
//							double x1 = dx;
//							dx = dy;
//							dy = xnumAnnotImgRegParamSrcImgWidth - x1;
//						}
//						else if(xnumAnnotImgRegParamRotation.equals("-180") || xnumAnnotImgRegParamRotation.equals("180")) {
//							dx = xnumAnnotImgRegParamSrcImgWidth - dx;
//							dy = xnumAnnotImgRegParamSrcImgHeight - dy;
//						}
//						else if(xnumAnnotImgRegParamRotation.equals("-270") || xnumAnnotImgRegParamRotation.equals("90")) {
//							double x1 = dx;
//							dx = xnumAnnotImgRegParamSrcImgHeight - dy;
//							dy = x1;	
//						}
//						
//						dx /= xnumAnnotImgRegParamSourceScale;
//						dy /= xnumAnnotImgRegParamSourceScale;						
//						
//			        	int bx = 0;
//			        	int by = 0;
//			        	
//			        	if(params.getBooleanParameterValue("dontTransform")) {
//			        		bx = (int)Math.round(dx);
//			        		by = (int)Math.round(dy);
//			        	}
//			        	else {
//				        	double ax = xnumAnnotImgRegParamSiftMatrix[0] * dx + xnumAnnotImgRegParamSiftMatrix[1] * dy + xnumAnnotImgRegParamSiftMatrix[2];
//				        	double ay = xnumAnnotImgRegParamSiftMatrix[3] * dx + xnumAnnotImgRegParamSiftMatrix[4] * dy + xnumAnnotImgRegParamSiftMatrix[5];
//							
//				        	if(!params.getBooleanParameterValue("AffineTransformOnly")) {
//					        	int bv = (int)Math.round(ay);
//					        	int bu = (int)Math.round(ax);
//					        	
//								double x1 = (double)(bu * bspline_intervals) / (double)(((int)((double)server.getWidth()/xnumAnnotImgRegParamTargetScale)+0.5) - 1) + 1.0F;
//								double y1 = (double)(bv * bspline_intervals) / (double)(((int)((double)server.getHeight()/xnumAnnotImgRegParamTargetScale)+0.5) - 1) + 1.0F;
//								
//								
//								bspline_swx.prepareForInterpolation(x1, y1, false);
//								double bspline_x_bv_bu = bspline_swx.interpolateI();
//					        	
//								bspline_swy.prepareForInterpolation(x1, y1, false);
//								double bspline_y_bv_bu = bspline_swy.interpolateI();
//								
//								 bx = (int)Math.round(bspline_x_bv_bu);
//								 by = (int)Math.round(bspline_y_bv_bu);
//								
//								
//				        	}
//				        	else {
//					        	bx = (int)Math.round(ax);
//				        		by = (int)Math.round(ay);
//				        	}
//			        	}
//			        	
//						bx *= xnumAnnotImgRegParamTargetScale;
//						by *= xnumAnnotImgRegParamTargetScale;
//						
//			        	int fx = (int)Math.round(bx / maskDownsampling);
//			        	int fy = (int)Math.round(by / maskDownsampling);
//			        	
//			        	if(fx < 0 || fx >= pathObjectImageMask.getWidth() || fy < 0 || fy >=  pathObjectImageMask.getHeight()) continue;
//			        	
//			        	int v = pathObjectImageMask.getRGB(fx, fy);
//			        	int d0 = v&0xff;
//			        	int d1 = (v>>8)&0xff;
//			        	int d2 = (v>>16)&0xff;
//						int r = d2*0x10000+d1*0x100+d0;
//					    
//			        	if(r == 0) continue; // This location doesn't have a cell.
//				        	
//			        	int pathObjectId = r - 1;  // pathObjectId starts at 1, since 0 means background
//				        	
//			        	PathObject cellPathObject = pathObjectList.get(pathObjectId);
//			        	cellToPathObjHashMap.put(cellId, cellPathObject);
//			        	
//			        	String scLabelId = cellToSCLabelHashMap.get(cellId);
//			        	
//			        	if(scLabelId != null) {
//			        		PathClass pathCls = PathClass.fromString(scLabelId);
//				        	cellPathObject.setPathClass(pathCls);
//			        	}
//			        	
//			        	double roiX = cellPathObject.getROI().getCentroidX();
//			        	double roiY = cellPathObject.getROI().getCentroidY();
//			        	double newDist = (new Point2D(bx, by).distance(roiX, roiY))*xnumAnnotImgRegParamDapiImgPxlSize;
//			        	MeasurementList pathObjMeasList = cellPathObject.getMeasurementList();
//			        	
//			        	if(pathObjMeasList.containsKey("xenium:cell:cell_id")) {
//			        		double minDist = pathObjMeasList.get("xenium:cell:displacement");
//			        		if(newDist < minDist) {
//			        			cellPathObject.setName(cellId);
//			        			pathObjMeasList.put("xenium:cell:displacement", newDist);
//			        			pathObjMeasList.put("xenium:cell:x_centroid", cx);
//			        			pathObjMeasList.put("xenium:cell:y_centroid", cy);
//	//		        			pathObjMeasList.put("xenium:cell:transcript_counts", transcriptCounts);
//	//		        			pathObjMeasList.put("xenium:cell:control_probe_counts", controlProbeCounts);
//	//		        			pathObjMeasList.put("xenium:cell:control_codeword_counts", controlCodewordCounts);
//	//		        			pathObjMeasList.put("xenium:cell:total_counts", totalCounts);
////			        			pathObjMeasList.put("xenium:cell:cell_area", cellArea);
////			        			pathObjMeasList.put("xenium:cell:nucleus_area", nucleusArea);
//			        		}
//			        	}
//			        	else {
//			        		cellPathObject.setName(cellId);
//		        			pathObjMeasList.put("xenium:cell:displacement", newDist);
//		        			pathObjMeasList.put("xenium:cell:x_centroid", cx);
//		        			pathObjMeasList.put("xenium:cell:y_centroid", cy);
//	//	        			pathObjMeasList.put("xenium:cell:transcript_counts", transcriptCounts);
//	//	        			pathObjMeasList.put("xenium:cell:control_probe_counts", controlProbeCounts);
//	//	        			pathObjMeasList.put("xenium:cell:control_codeword_counts", controlCodewordCounts);
//	//	        			pathObjMeasList.put("xenium:cell:total_counts", totalCounts);
////		        			pathObjMeasList.put("xenium:cell:cell_area", cellArea);
////		        			pathObjMeasList.put("xenium:cell:nucleus_area", nucleusArea);     		        
//			        	}
//		        	}		        	
//		        	
//			        singleCellGzipReader.close();
//				} else if (java.nio.file.Files.exists(cellsParquetFileJPath)) {
//				
//					Path parquetFilePath = new Path(cellsParquetFileJPath.toString());
//					
//					Configuration conf = new Configuration();
//					conf.set("fs.defaultFS", "file:///");
//									
//					InputFile parquetFile = HadoopInputFile.fromPath(parquetFilePath, conf);
//					ParquetReader<GenericRecord> parquetReader = AvroParquetReader.<GenericRecord>builder(parquetFile).build();
//					
//					GenericRecord parquetRecord;
//					while ((parquetRecord = parquetReader.read()) != null) {
//			        	String cellId = parquetRecord.get("cell_id").toString();
//			        	
//	//		        	double transcriptCounts = (double) parquetRecord.get("transcript_counts");
//	//		        	double controlProbeCounts = (double) parquetRecord.get("control_probe_counts");
//			        	
//	//		        	double genomic_control_counts = (double) parquetRecord.get("controlProbeCounts");
//	//		        	double unassigned_codeword_counts = (double) parquetRecord.get("unassigned_codeword_counts");
//	//		        	double deprecated_codeword_counts = (double) parquetRecord.get("deprecated_codeword_counts");
//	//		        	double control_probe_counts = (double) parquetRecord.get("control_probe_counts");
//	//		        	double nucleus_count = (double) parquetRecord.get("nucleus_count");
//	//		        	String segmentation_method = parquetRecord.get("segmentation_method").toString();
//			        	
//	//		        	double controlCodewordCounts = (double) parquetRecord.get("control_codeword_counts");
//	//		        	double totalCounts = (double) parquetRecord.get("total_counts");
//			        	
////			        	double cellArea = (double) parquetRecord.get("cell_area");
////			        	double nucleusArea = (double) parquetRecord.get("nucleus_area");
//			        	
//			        	double cx = (double) parquetRecord.get("x_centroid");
//			        	double cy = (double) parquetRecord.get("y_centroid");
//			        	
//			        	double dx = cx/xnumAnnotImgRegParamDapiImgPxlSize;
//			        	double dy = cy/xnumAnnotImgRegParamDapiImgPxlSize;
//			        	
//			        	if(xnumAnnotImgRegParamFlipVert) {
//							dy = xnumAnnotImgRegParamSrcImgHeight - dy;
//						}
//						
//						if(xnumAnnotImgRegParamFlipHori) {
//							dx = xnumAnnotImgRegParamSrcImgWidth - dx;
//						}
//	
//						if(xnumAnnotImgRegParamRotation.equals("-90") || xnumAnnotImgRegParamRotation.equals("270")) {
//							double x1 = dx;
//							dx = dy;
//							dy = xnumAnnotImgRegParamSrcImgWidth - x1;
//						}
//						else if(xnumAnnotImgRegParamRotation.equals("-180") || xnumAnnotImgRegParamRotation.equals("180")) {
//							dx = xnumAnnotImgRegParamSrcImgWidth - dx;
//							dy = xnumAnnotImgRegParamSrcImgHeight - dy;
//						}
//						else if(xnumAnnotImgRegParamRotation.equals("-270") || xnumAnnotImgRegParamRotation.equals("90")) {
//							double x1 = dx;
//							dx = xnumAnnotImgRegParamSrcImgHeight - dy;
//							dy = x1;	
//						}
//						
//						dx /= xnumAnnotImgRegParamSourceScale;
//						dy /= xnumAnnotImgRegParamSourceScale;						
//						
//			        	int bx = 0;
//			        	int by = 0;
//			        	
//			        	if(params.getBooleanParameterValue("dontTransform")) {
//			        		bx = (int)Math.round(dx);
//			        		by = (int)Math.round(dy);
//			        	}
//			        	else {
//				        	double ax = xnumAnnotImgRegParamSiftMatrix[0] * dx + xnumAnnotImgRegParamSiftMatrix[1] * dy + xnumAnnotImgRegParamSiftMatrix[2];
//				        	double ay = xnumAnnotImgRegParamSiftMatrix[3] * dx + xnumAnnotImgRegParamSiftMatrix[4] * dy + xnumAnnotImgRegParamSiftMatrix[5];
//							
//				        	if(!params.getBooleanParameterValue("AffineTransformOnly")) {
//					        	int bv = (int)Math.round(ay);
//					        	int bu = (int)Math.round(ax);
//					        	
//								double x1 = (double)(bu * bspline_intervals) / (double)(((int)((double)server.getWidth()/xnumAnnotImgRegParamTargetScale)+0.5) - 1) + 1.0F;
//								double y1 = (double)(bv * bspline_intervals) / (double)(((int)((double)server.getHeight()/xnumAnnotImgRegParamTargetScale)+0.5) - 1) + 1.0F;
//								
//								
//								bspline_swx.prepareForInterpolation(x1, y1, false);
//								double bspline_x_bv_bu = bspline_swx.interpolateI();
//					        	
//								bspline_swy.prepareForInterpolation(x1, y1, false);
//								double bspline_y_bv_bu = bspline_swy.interpolateI();
//								
//								 bx = (int)Math.round(bspline_x_bv_bu);
//								 by = (int)Math.round(bspline_y_bv_bu);
//				        	}
//				        	else {
//					        	bx = (int)Math.round(ax);
//				        		by = (int)Math.round(ay);
//				        	}
//			        	}
//			        	
//						bx *= xnumAnnotImgRegParamTargetScale;
//						by *= xnumAnnotImgRegParamTargetScale;
//						
//			        	int fx = (int)Math.round(bx / maskDownsampling);
//			        	int fy = (int)Math.round(by / maskDownsampling);
//			        	
//			        	if(fx < 0 || fx >= pathObjectImageMask.getWidth() || fy < 0 || fy >=  pathObjectImageMask.getHeight()) continue;
//			        	
//			        	int v = pathObjectImageMask.getRGB(fx, fy);
//			        	int d0 = v&0xff;
//			        	int d1 = (v>>8)&0xff;
//			        	int d2 = (v>>16)&0xff;
//						int r = d2*0x10000+d1*0x100+d0;
//					    
//			        	if(r == 0) continue; // This location doesn't have a cell.
//				        	
//			        	int pathObjectId = r - 1;  // pathObjectId starts at 1, since 0 means background
//				        	
//			        	PathObject cellPathObject = pathObjectList.get(pathObjectId);
//			        	cellToPathObjHashMap.put(cellId, cellPathObject);
//			        	
//			        	String scLabelId = cellToSCLabelHashMap.get(cellId);
//			        	
//			        	if(scLabelId != null) {
//			        		PathClass pathCls = PathClass.fromString(scLabelId);
//				        	cellPathObject.setPathClass(pathCls);
//			        	}
//			        	
//			        	double roiX = cellPathObject.getROI().getCentroidX();
//			        	double roiY = cellPathObject.getROI().getCentroidY();
//			        	double newDist = (new Point2D(bx, by).distance(roiX, roiY))*xnumAnnotImgRegParamDapiImgPxlSize;
//			        	MeasurementList pathObjMeasList = cellPathObject.getMeasurementList();
//			        	
//			        	if(pathObjMeasList.containsKey("xenium:cell:cell_id")) {
//			        		double minDist = pathObjMeasList.get("xenium:cell:displacement");
//			        		if(newDist < minDist) {
//			        			cellPathObject.setName(cellId);
//			        			pathObjMeasList.put("xenium:cell:displacement", newDist);
//			        			pathObjMeasList.put("xenium:cell:x_centroid", cx);
//			        			pathObjMeasList.put("xenium:cell:y_centroid", cy);
//	//		        			pathObjMeasList.put("xenium:cell:transcript_counts", transcriptCounts);
//	//		        			pathObjMeasList.put("xenium:cell:control_probe_counts", controlProbeCounts);
//	//		        			pathObjMeasList.put("xenium:cell:control_codeword_counts", controlCodewordCounts);
//	//		        			pathObjMeasList.put("xenium:cell:total_counts", totalCounts);
////			        			pathObjMeasList.put("xenium:cell:cell_area", cellArea);
////			        			pathObjMeasList.put("xenium:cell:nucleus_area", nucleusArea);
//			        		}
//			        	}
//			        	else {
//			        		cellPathObject.setName(cellId);
//		        			pathObjMeasList.put("xenium:cell:displacement", newDist);
//		        			pathObjMeasList.put("xenium:cell:x_centroid", cx);
//		        			pathObjMeasList.put("xenium:cell:y_centroid", cy);
//	//	        			pathObjMeasList.put("xenium:cell:transcript_counts", transcriptCounts);
//	//	        			pathObjMeasList.put("xenium:cell:control_probe_counts", controlProbeCounts);
//	//	        			pathObjMeasList.put("xenium:cell:control_codeword_counts", controlCodewordCounts);
//	//	        			pathObjMeasList.put("xenium:cell:total_counts", totalCounts);
////		        			pathObjMeasList.put("xenium:cell:cell_area", cellArea);
////		        			pathObjMeasList.put("xenium:cell:nucleus_area", nucleusArea);     		        
//			        	}
//					}
//					parquetReader.close();
//	
//				} else {
//					throw new Exception("cell file error");
//				}	
//			     
//				
//				/*
//	             * Read feature matrix data
//	             */
//					
//				
//				java.nio.file.Path cellFeatureMatrixH5FileJPath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix.h5");
//				java.nio.file.Path barcodeFileJPath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix", "barcodes.tsv.gz");
//				java.nio.file.Path featureFileJPath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix", "features.tsv.gz");
//				java.nio.file.Path matrixFileJPath = java.nio.file.Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix", "matrix.mtx.gz");
//				
//					
//				if (java.nio.file.Files.exists(barcodeFileJPath) && java.nio.file.Files.exists(featureFileJPath) && java.nio.file.Files.exists(matrixFileJPath) ) {
//					GZIPInputStream barcodeGzipStream = new GZIPInputStream(new FileInputStream(barcodeFileJPath.toString()));
//					try (BufferedReader barcodeGzipReader = new BufferedReader(new InputStreamReader(barcodeGzipStream))) {
//						List<String> barcodeList = new ArrayList<>();
//						
//						String barcodeNextRecord;
//						while ((barcodeNextRecord = barcodeGzipReader.readLine()) != null) {
//							barcodeList.add(barcodeNextRecord);
//						}
//						
//						List<String> featureIdList = new ArrayList<>();
//						List<String> featureNameList = new ArrayList<>();
//						List<String> featureTypeList = new ArrayList<>();
//						
//						GZIPInputStream featureGzipStream = new GZIPInputStream(new FileInputStream(featureFileJPath.toString()));
//						try (BufferedReader featureGzipReader = new BufferedReader(new InputStreamReader(featureGzipStream))) {
//							String featureNextRecord;
//							while ((featureNextRecord = featureGzipReader.readLine()) != null) {
//								String[] featureNextRecordArray = featureNextRecord.split("\t");
//								featureIdList.add(featureNextRecordArray[0]);
//								featureNameList.add(featureNextRecordArray[1]);
//								featureTypeList.add(featureNextRecordArray[2]);
//							}
//						}
//						
//						GZIPInputStream matrixGzipStream = new GZIPInputStream(new FileInputStream(matrixFileJPath.toString()));
//						try (BufferedReader matrixGzipReader = new BufferedReader(new InputStreamReader(matrixGzipStream), '\t')) {
//							matrixGzipReader.readLine();
//							matrixGzipReader.readLine();
//							matrixGzipReader.readLine();
//							
//							int[][] matrix = new int[featureNameList.size()][barcodeList.size()];
//							
//							String matrixNextRecord;
//							while ((matrixNextRecord = matrixGzipReader.readLine()) != null) {
//								String[] matrixNextRecordArray = matrixNextRecord.split(" ");
//								int f = Integer.parseInt(matrixNextRecordArray[0])-1;
//								int b = Integer.parseInt(matrixNextRecordArray[1])-1;
//								int v = Integer.parseInt(matrixNextRecordArray[2]);
//								
//								matrix[f][b] = v;
//							}
//							
//							IntStream.range(0, barcodeList.size()).parallel().forEach(b -> {
//								if(cellToPathObjHashMap.containsKey(barcodeList.get(b))) {
//							    	PathObject c = cellToPathObjHashMap.get(barcodeList.get(b));
//							    	MeasurementList pathObjMeasList = c.getMeasurementList();
//							    	
//							    	for(int f = 0; f < featureNameList.size(); f ++) {	
////							    		if(!params.getBooleanParameterValue("inclBlankCodeword") && featureTypeList.get(f).compareTo("Blank Codeword")==0) continue;
////							    		if(!params.getBooleanParameterValue("inclUnassignedCodeword") && featureTypeList.get(f).compareTo("Unassigned Codeword")==0) continue;
////							    		if(!params.getBooleanParameterValue("inclDeprecatedCodeword") && featureTypeList.get(f).compareTo("Deprecated Codeword")==0) continue;
////							    		if(!params.getBooleanParameterValue("inclIntergenicRegion") && featureTypeList.get(f).compareTo("Genomic Control")==0) continue;
////										if(!params.getBooleanParameterValue("inclGeneExpr") && featureTypeList.get(f).compareTo("Gene Expression")==0) continue;
////										if(!params.getBooleanParameterValue("inclNegCtrlCodeword") && featureTypeList.get(f).compareTo("Negative Control Codeword")==0) continue;
////										if(!params.getBooleanParameterValue("inclNegCtrlProbe") && featureTypeList.get(f).compareTo("Negative Control Probe")==0) continue;
//							    		
//										if (featureTypeList.get(f).compareTo("Gene Expression")==0) {
//											pathObjMeasList.put("transcript:"+featureNameList.get(f), matrix[f][b]);   
//										}
//							    	}
//								}
//							});
//						}
//					}
//				} else if (java.nio.file.Files.exists(cellFeatureMatrixH5FileJPath)) {
//					String cellFeatureMatrixH5FilePath = cellFeatureMatrixH5FileJPath.toString();
//					long fileId = H5.H5Fopen(cellFeatureMatrixH5FilePath, HDF5Constants.H5F_ACC_RDONLY, HDF5Constants.H5P_DEFAULT);
//					String[] barcodes     = readFixedStringDataset(fileId, "/matrix/barcodes");
//					String[] geneNames    = readFixedStringDataset(fileId, "/matrix/features/name");
//	//				String[] geneIds      = readFixedStringDataset(fileId, "/matrix/features/id");
//					String[] featureTypes = readFixedStringDataset(fileId, "/matrix/features/feature_type");
//					
//					int[] data    = readInt1D(fileId, "/matrix/data");
//					int[] indices = readInt1D(fileId, "/matrix/indices");
//					int[] indptr  = readInt1D(fileId, "/matrix/indptr");
//					// Print data for the first cell
//				     
//					IntStream.range(0, barcodes.length).parallel().forEach(c -> {
//						if(cellToPathObjHashMap.containsKey(barcodes[c])) {
//							int[] counts = new int[geneNames.length];
//							int start = indptr[c], end = indptr[c+1];
//							for(int k = start; k < end; k ++) {
//								counts[indices[k]] = data[k];
//							}
//							
//					    	PathObject o = cellToPathObjHashMap.get(barcodes[c]);
//					    	MeasurementList pathObjMeasList = o.getMeasurementList();
//					    	
//					    	IntStream.range(0, featureTypes.length).parallel().forEach(f -> {
//	//				    		if(!params.getBooleanParameterValue("inclBlankCodeword") && featureTypes[f].compareTo("Blank Codeword")==0) return;
//	//				    		if(!params.getBooleanParameterValue("inclUnassignedCodeword") && featureTypes[f].compareTo("Unassigned Codeword")==0) return;
//	//				    		if(!params.getBooleanParameterValue("inclDeprecatedCodeword") && featureTypes[f].compareTo("Deprecated Codeword")==0) return;
//	//				    		if(!params.getBooleanParameterValue("inclIntergenicRegion") && featureTypes[f].compareTo("Genomic Control")==0) return;
//	//							if(!params.getBooleanParameterValue("inclGeneExpr") && featureTypes[f].compareTo("Gene Expression")==0) return;
//	//							if(!params.getBooleanParameterValue("inclNegCtrlCodeword") && featureTypes[f].compareTo("Negative Control Codeword")==0) return;
//	//							if(!params.getBooleanParameterValue("inclNegCtrlProbe") && featureTypes[f].compareTo("Negative Control Probe")==0) return;
//	
////					    		if(featureTypes[f].compareTo("Blank Codeword")==0) return;
////					    		if(featureTypes[f].compareTo("Unassigned Codeword")==0) return;
////					    		if(featureTypes[f].compareTo("Deprecated Codeword")==0) return;
////					    		if(featureTypes[f].compareTo("Genomic Control")==0) return;
////								if(featureTypes[f].compareTo("Negative Control Codeword")==0) return;
////								if(featureTypes[f].compareTo("Negative Control Probe")==0) return;
//					    		
//								if (featureTypes[f].compareTo("Gene Expression")==0) {
//									pathObjMeasList.put("transcript:"+geneNames[f], counts[f]);  
//									
//								}
//					    	});
//						}
//					});
//					
//					H5.H5Fclose(fileId);
//				   
//				} else {
//					throw new Exception("Load matrix data failed");
//				}
//				
//				
//				
//				
//				
//				if(params.getBooleanParameterValue("removeUnlabeledCells")) {
//					for(PathObject c: pathObjectList) {
//						if(c.getPathClass() == null) {
//							c.getParent().removeChildObject(c);
//						}
//					}
//				}
//
//		        hierarchy.getSelectionModel().setSelectedObject(null);
//			}
//			catch(Exception e) {	
//				lastResults = e.getMessage();
//				if (QuPathGUI.getInstance() != null) Dialogs.showErrorMessage("Error", lastResults);
//				logger.error(lastResults);
//				
//				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
//			}				
//			
//			if (Thread.currentThread().isInterrupted()) {
//				lastResults =  "Interrupted!";
//				if (QuPathGUI.getInstance() != null) Dialogs.showWarningNotification("Warning", lastResults);
//				logger.warn(lastResults);
//				
//				return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
//			}
//			
//			return resultPathObjectList;
//		}
		
		private JSONObject imgRegParamJsonObj = null;
		@Override
		public Collection<PathObject> runDetection(ImageData<BufferedImage> imageData, ParameterList params, ROI pathROI) throws IOException {
		    ImageServer<BufferedImage> server = imageData.getServer();
		    PathObjectHierarchy hierarchy = imageData.getHierarchy();
		    List<PathObject> resultPathObjectList = new ArrayList<>(hierarchy.getRootObject().getChildObjects());
		    
		    // Load linear transformation parameters (if available)
//		    JSONObject imgRegParamJsonObj = null;
		    java.nio.file.Path regParamsPath = Paths.get(xnumAntnXnumFldrProp.get(), "registration_params.json");
		    if (Files.exists(regParamsPath)) {
		        try (InputStream is = Files.newInputStream(regParamsPath)) {
		            String jsonTxt = IOUtils.toString(is, StandardCharsets.UTF_8);
		            imgRegParamJsonObj = new JSONObject(jsonTxt);
		        }
		    }
		    // Extract transformation parameters with defaults if JSON is missing
		    int srcImgWidth  = (imgRegParamJsonObj != null) ? imgRegParamJsonObj.getInt("xnumAnnotImgRegParamSrcImgWidth")  : 1;
		    int srcImgHeight = (imgRegParamJsonObj != null) ? imgRegParamJsonObj.getInt("xnumAnnotImgRegParamSrcImgHeight") : 1;
		    boolean flipHori = (imgRegParamJsonObj != null) && imgRegParamJsonObj.getBoolean("xnumAnnotImgRegParamFlipHori");
		    boolean flipVert = (imgRegParamJsonObj != null) && imgRegParamJsonObj.getBoolean("xnumAnnotImgRegParamFlipVert");
		    double dapiPixelSize = (imgRegParamJsonObj != null) ? imgRegParamJsonObj.getDouble("xnumAnnotImgRegParamDapiImgPxlSize") : 1.0;
		    String rotation = (imgRegParamJsonObj != null) ? imgRegParamJsonObj.getString("xnumAnnotImgRegParamRotation") : "0";
		    double[] siftMatrix = (imgRegParamJsonObj != null) 
		            ? IntStream.range(0, 6).mapToDouble(i -> imgRegParamJsonObj.getJSONArray("xnumAnnotImgRegParamSiftMatrix").getDouble(i)).toArray()
		            : new double[]{1,0,0, 0,1,0}; // identity matrix if not provided
		    double sourceScale = (imgRegParamJsonObj != null) ? imgRegParamJsonObj.getDouble("xnumAnnotImgRegParamSourceScale") : 1.0;
		    double targetScale = (imgRegParamJsonObj != null) ? imgRegParamJsonObj.getDouble("xnumAnnotImgRegParamTargetScale") : 1.0;
		    
		    // Load non-linear (B-spline) transformation if AffineTransformOnly is false
		    BSplineModel bsplineX = null, bsplineY = null;
		    int bsplineIntervals = 0;
		    if (!params.getBooleanParameterValue("AffineTransformOnly")) {
		        java.nio.file.Path transfPath = Paths.get(xnumAntnXnumFldrProp.get(), "direct_transf.txt");
		        if (Files.exists(transfPath)) {
		            bsplineIntervals = numberOfIntervalsOfTransformation(transfPath.toString());
		            double[][] bspline_cx = new double[bsplineIntervals+3][bsplineIntervals+3];
		            double[][] bspline_cy = new double[bsplineIntervals+3][bsplineIntervals+3];
		            loadTransformation(transfPath.toString(), bspline_cx, bspline_cy);
		            bsplineX = new BSplineModel(bspline_cx);
		            bsplineY = new BSplineModel(bspline_cy);
		        }
		    }
		    
		    // Get the list of selected annotation objects that contain cells
		    List<PathObject> selectedAnnotations = hierarchy.getSelectionModel().getSelectedObjects().stream()
		            .filter(obj -> obj.isAnnotation() && obj.hasChildObjects())
		            .collect(Collectors.toList());
		    if (selectedAnnotations.isEmpty()) {
		        throw new IOException("No annotation with child objects selected for Xenium import.");
		    }
		    
		    // Prepare the cell mask image (each cell gets a unique color code in the mask)
		    int maskDownsampling = params.getIntParameterValue("maskDownsampling");
		    int maskWidth  = (int) Math.round(server.getWidth()  / (double) maskDownsampling);
		    int maskHeight = (int) Math.round(server.getHeight() / (double) maskDownsampling);
		    BufferedImage cellMaskImage = new BufferedImage(maskWidth, maskHeight, BufferedImage.TYPE_INT_RGB);
		    Graphics2D g2d = cellMaskImage.createGraphics();
		    try {
		        g2d.setBackground(Color.BLACK);
		        g2d.clearRect(0, 0, maskWidth, maskHeight);
		        g2d.setClip(0, 0, maskWidth, maskHeight);
		        // Scale drawing to image coordinate space
		        g2d.scale(1.0/maskDownsampling, 1.0/maskDownsampling);
		        // Draw each cell (child object) with a unique color
		        List<PathObject> cellObjectList = new ArrayList<>();
		        int cellIndex = 1;
		        for (PathObject annotation : selectedAnnotations) {
		            for (PathObject cell : annotation.getChildObjects()) {
		                cellObjectList.add(cell);
		                // Encode index into RGB (24-bit color)
		                int rgb_r = (cellIndex & 0xFF0000) >> 16;
		                int rgb_g = (cellIndex & 0xFF00) >> 8;
		                int rgb_b = cellIndex & 0xFF;
		                g2d.setColor(new Color(rgb_r, rgb_g, rgb_b));
		                // Draw the cell shape
		                ROI roi = cell.getROI();
		                if (roi != null) {
		                    Shape shape = roi.getShape();
		                    g2d.fill(shape);
		                }
		                cellIndex++;
		                if (cellIndex >= 0xFFFFFF) {
		                    throw new IOException("Cell count overflow: more than 16,777,215 cells.");
		                }
		            }
		        }
		        // Now cellObjectList[i] corresponds to the cell with color code (i+1)
		        // (Background is 0 which we will treat as "no cell")
		        
		        // Read cluster labels (if available) to map cell_id -> cluster class
		        Map<String, String> cellToClusterMap = new HashMap<>();
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
		                    cellToClusterMap.put(cellId, clusterId);
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
		                                cellToClusterMap.put(cellId, clusterId);
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
		        
		        // Read single-cell data (cells coordinates and IDs) and map each to a PathObject
		        Map<String, PathObject> cellIdToObject = new HashMap<>();
		        java.nio.file.Path cellsCsvGz = Paths.get(xnumAntnXnumFldrProp.get(), "cells.csv.gz");
		        java.nio.file.Path cellsParquet = Paths.get(xnumAntnXnumFldrProp.get(), "cells.parquet");
		        if (Files.exists(cellsCsvGz)) {
		            // Stream through cells.csv.gz
		            try (BufferedReader reader = new BufferedReader(
		                     new InputStreamReader(new GZIPInputStream(Files.newInputStream(cellsCsvGz)), StandardCharsets.UTF_8))) {
		                reader.readLine(); // skip header line
		                String line;
		                while ((line = reader.readLine()) != null) {
		                    String[] fields = line.split(",");
		                    if (fields.length < 3) continue;
		                    String cellId = fields[0].replace("\"", "");
		                    // Parse centroid coordinates
		                    double cx = Double.parseDouble(fields[1]);
		                    double cy = Double.parseDouble(fields[2]);
		                    // Compute image coordinates (dx, dy) in original image space
		                    double dx = cx / dapiPixelSize;
		                    double dy = cy / dapiPixelSize;
		                    if (flipVert) dy = srcImgHeight - dy;
		                    if (flipHori) dx = srcImgWidth - dx;
		                    // Apply 90/180/270 rotations if needed
		                    switch (rotation) {
		                        case "-90": case "270":
		                            double tmpX = dx;
		                            dx = dy;
		                            dy = srcImgWidth - tmpX;
		                            break;
		                        case "-180": case "180":
		                            dx = srcImgWidth - dx;
		                            dy = srcImgHeight - dy;
		                            break;
		                        case "-270": case "90":
		                            double tmpX2 = dx;
		                            dx = srcImgHeight - dy;
		                            dy = tmpX2;
		                            break;
		                        default:
		                            // no rotation or 0/360
		                    }
		                    // Scale source to reference
		                    dx /= sourceScale;
		                    dy /= sourceScale;
		                    // Apply affine transform (siftMatrix)
		                    int bx, by;
		                    if (params.getBooleanParameterValue("dontTransform")) {
		                        // Use untransformed (just scaled/rotated) coordinates
		                        bx = (int)Math.round(dx);
		                        by = (int)Math.round(dy);
		                    } else {
			                    double ax = siftMatrix[0]*dx + siftMatrix[1]*dy + siftMatrix[2];
			                    double ay = siftMatrix[3]*dx + siftMatrix[4]*dy + siftMatrix[5];
			                    
		                    	if (!params.getBooleanParameterValue("AffineTransformOnly") && bsplineX != null && bsplineY != null) {
			                        // Apply non-linear B-spline transform on top of affine
			                        int bu = (int)Math.round(ax);
			                        int bv = (int)Math.round(ay);
			                        // Normalize to [1, intervals] for BSpline model
			                        double xNorm = (double)(bu * bsplineIntervals) / (double)(((int)((double)(server.getWidth()/targetScale) + 0.5)) - 1) + 1.0;
			                        double yNorm = (double)(bv * bsplineIntervals) / (double)(((int)((double)(server.getHeight()/targetScale) + 0.5)) - 1) + 1.0;
			                        bsplineX.prepareForInterpolation(xNorm, yNorm, false);
			                        bsplineY.prepareForInterpolation(xNorm, yNorm, false);
			                        bx = (int)Math.round(bsplineX.interpolateI());
			                        by = (int)Math.round(bsplineY.interpolateI());
		                    	} else {
			                        // Only affine
			                        bx = (int)Math.round(ax);
			                        by = (int)Math.round(ay);
			                    }
		                    }
		                    // Scale back up to target image coordinates
		                    bx = (int)Math.round(bx * targetScale);
		                    by = (int)Math.round(by * targetScale);
		                    // Map the transformed coordinate to a cell object via the mask
		                    int fx = bx / maskDownsampling;
		                    int fy = by / maskDownsampling;
		                    if (fx < 0 || fx >= maskWidth || fy < 0 || fy >= maskHeight) continue; // skip if outside image bounds
		                    int rgb = cellMaskImage.getRGB(fx, fy);
		                    
		                    // int idCode = ((rgb >> 16) & 0xFF) * 0x10000 + ((rgb >> 8) & 0xFF) * 0x100 + (rgb & 0xFF);
		                    
		                    int rgb_r = ((rgb >> 16) & 0xFF) * 0x10000;
		                    int rgb_g = ((rgb >>  8) & 0xFF) * 0x100;
		                    int rgb_b = rgb & 0xFF;
		                    
		                    int idCode = rgb_r + rgb_g + rgb_b;
		                    
		                    if (idCode == 0) continue; // background, no cell at this location
		                    int cellIndexInList = idCode - 1;
		                    if (cellIndexInList < 0 || cellIndexInList >= cellObjectList.size()) continue;
		                    PathObject cellObj = cellObjectList.get(cellIndexInList);
		                    // Map cell ID to its PathObject for later use
		                    cellIdToObject.put(cellId, cellObj);
		                    // Assign cluster class if available
		                    if (cellToClusterMap.containsKey(cellId)) {
		                        cellObj.setPathClass(PathClass.fromString(cellToClusterMap.get(cellId)));
		                    }
		                    // Compute displacement between transformed coordinate and actual ROI centroid
		                    Point2D roiCentroid = new Point2D(cellObj.getROI().getCentroidX(), cellObj.getROI().getCentroidY());
		                    double dist = roiCentroid != null ? roiCentroid.distance(bx, by) * dapiPixelSize : 0.0;
		                    MeasurementList meas = cellObj.getMeasurementList();
		                    // Record the cell ID and centroid in the measurements, updating if this cellId was mapped before
		                    if (!meas.containsKey("xenium:cell:cell_id")) {
		                        // First time seeing this cell, store values
		                        cellObj.setName(cellId);
//		                        meas.put("xenium:cell:cell_id", cellId);
		                        meas.put("xenium:cell:displacement", dist);
		                        meas.put("xenium:cell:x_centroid", cx);
		                        meas.put("xenium:cell:y_centroid", cy);
		                    } else {
		                        // If this cellObj was mapped to a different cellId before (overlap), keep the one with smaller displacement
		                        double prevDist = meas.get("xenium:cell:displacement");
		                        if (dist < prevDist) {
		                            cellObj.setName(cellId);
//		                            meas.put("xenium:cell:cell_id", cellId);
		                            meas.put("xenium:cell:displacement", dist);
		                            meas.put("xenium:cell:x_centroid", cx);
		                            meas.put("xenium:cell:y_centroid", cy);
		                        }
		                    }
		                }
		            }
		        } else if (Files.exists(cellsParquet)) {
		            // Read from Parquet using AvroParquetReader
		            try (ParquetReader<GenericRecord> parquetReader = AvroParquetReader.<GenericRecord>builder(HadoopInputFile.fromPath(new Path(cellsParquet.toString()), new Configuration())).build()) {
		                GenericRecord record;
		                while ((record = parquetReader.read()) != null) {
		                    String cellId = record.get("cell_id").toString();
		                    double cx = (double) record.get("x_centroid");
		                    double cy = (double) record.get("y_centroid");
		                    // (The other fields like transcript_counts, etc., are read if needed, but omitted here for brevity)
		                    // Apply same coordinate transform as above
		                    double dx = cx / dapiPixelSize;
		                    double dy = cy / dapiPixelSize;
		                    if (flipVert) dy = srcImgHeight - dy;
		                    if (flipHori) dx = srcImgWidth - dx;
		                    switch (rotation) {
		                        case "-90": case "270":
		                            double tmpX = dx; dx = dy; dy = srcImgWidth - tmpX; break;
		                        case "-180": case "180":
		                            dx = srcImgWidth - dx; dy = srcImgHeight - dy; break;
		                        case "-270": case "90":
		                            double tmpX2 = dx; dx = srcImgHeight - dy; dy = tmpX2; break;
		                        default:
		                    }
		                    // Scale source to reference
		                    dx /= sourceScale;
		                    dy /= sourceScale;

		                    int bx, by;
		                    if (params.getBooleanParameterValue("dontTransform")) {
		                        bx = (int)Math.round(dx);
		                        by = (int)Math.round(dy);
		                    } else {
			                    double ax = siftMatrix[0]*dx + siftMatrix[1]*dy + siftMatrix[2];
			                    double ay = siftMatrix[3]*dx + siftMatrix[4]*dy + siftMatrix[5];
		                    
		                    	if (!params.getBooleanParameterValue("AffineTransformOnly") && bsplineX != null && bsplineY != null) {
		                    		int bu = (int)Math.round(ax);
			                        int bv = (int)Math.round(ay);
			                        double xNorm = (double)(bu * bsplineIntervals) / (double)(((int)((double)(server.getWidth()/targetScale) + 0.5)) - 1) + 1.0;
			                        double yNorm = (double)(bv * bsplineIntervals) / (double)(((int)((double)(server.getHeight()/targetScale) + 0.5)) - 1) + 1.0;
			                        
			                        bsplineX.prepareForInterpolation(xNorm, yNorm, false);
			                        bsplineY.prepareForInterpolation(xNorm, yNorm, false);
			                        bx = (int)Math.round(bsplineX.interpolateI());
			                        by = (int)Math.round(bsplineY.interpolateI());
			                    } else {
			                        bx = (int)Math.round(ax);
			                        by = (int)Math.round(ay);
			                    }
		                    }
		                    bx = (int)Math.round(bx * targetScale);
		                    by = (int)Math.round(by * targetScale);
		                    int fx = bx / maskDownsampling;
		                    int fy = by / maskDownsampling;
		                    if (fx < 0 || fx >= maskWidth || fy < 0 || fy >= maskHeight) continue;
		                    int rgb = cellMaskImage.getRGB(fx, fy);
		                    int idCode = ((rgb>>16)&0xFF)*0x10000 + ((rgb>>8)&0xFF)*0x100 + (rgb & 0xFF);
		                    if (idCode == 0) continue;
		                    int cellIndexInList = idCode - 1;
		                    if (cellIndexInList < 0 || cellIndexInList >= cellObjectList.size()) continue;
		                    PathObject cellObj = cellObjectList.get(cellIndexInList);
		                    cellIdToObject.put(cellId, cellObj);
		                    if (cellToClusterMap.containsKey(cellId)) {
		                        cellObj.setPathClass(PathClass.fromString(cellToClusterMap.get(cellId)));
		                    }
		                    Point2D roiCentroid = new Point2D(cellObj.getROI().getCentroidX(), cellObj.getROI().getCentroidY());
		                    double dist = roiCentroid != null ? roiCentroid.distance(bx, by) * dapiPixelSize : 0.0;
		                    MeasurementList meas = cellObj.getMeasurementList();
		                    if (!meas.containsKey("xenium:cell:cell_id")) {
		                        cellObj.setName(cellId);
//		                        meas.put("xenium:cell:cell_id", cellId);
		                        meas.put("xenium:cell:displacement", dist);
		                        meas.put("xenium:cell:x_centroid", cx);
		                        meas.put("xenium:cell:y_centroid", cy);
		                    } else {
		                        double prevDist = meas.get("xenium:cell:displacement");
		                        if (dist < prevDist) {
		                            cellObj.setName(cellId);
//		                            meas.put("xenium:cell:cell_id", cellId);
		                            meas.put("xenium:cell:displacement", dist);
		                            meas.put("xenium:cell:x_centroid", cx);
		                            meas.put("xenium:cell:y_centroid", cy);
		                        }
		                    }
		                }
		            }
		        } else {
		            throw new IOException("Cell coordinates file not found (expected cells.csv.gz or cells.parquet).");
		        }
		        
		        // Read feature matrix (gene expression data) and add transcript counts
		        java.nio.file.Path h5File   = Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix.h5");
		        java.nio.file.Path cellFeatureMatrixTarGzFilePath  = Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix.tar.gz");
		        java.nio.file.Path barcodeTsv  = Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix", "barcodes.tsv.gz");
		        java.nio.file.Path featureTsv  = Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix", "features.tsv.gz");
		        java.nio.file.Path matrixMtx   = Paths.get(xnumAntnXnumFldrProp.get(), "cell_feature_matrix", "matrix.mtx.gz");
		        if (Files.exists(barcodeTsv) && Files.exists(featureTsv) && Files.exists(matrixMtx)) {
		            // 10x Genomics format: barcodes, features, matrix market
		            // Read barcodes (cell IDs)
		            List<String> barcodes = new ArrayList<>();
		            try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(barcodeTsv)), StandardCharsets.UTF_8))) {
		                String line;
		                while ((line = br.readLine()) != null) {
		                    barcodes.add(line.trim());
		                }
		            }
		            // Map barcode index to PathObject for quick access (or null if not present in cellIdToObject)
		            int numCells = barcodes.size();
		            PathObject[] cellByIndex = new PathObject[numCells];
		            for (int i = 0; i < numCells; i++) {
		                String cellId = barcodes.get(i);
		                cellByIndex[i] = cellIdToObject.get(cellId); // may be null if cell not mapped (e.g. filtered out)
		            }
		            // Read features (gene names and types)
		            List<String> featureNames = new ArrayList<>();
		            List<String> featureTypes = new ArrayList<>();
		            try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(featureTsv)), StandardCharsets.UTF_8))) {
		                String line;
		                while ((line = br.readLine()) != null) {
		                    String[] cols = line.split("\t");
		                    if (cols.length >= 3) {
		                        featureNames.add(cols[1]);       // gene name
		                        featureTypes.add(cols[2]);       // feature type (e.g. "Gene Expression", "Negative Control", etc.)
		                    }
		                }
		            }
		            // Stream through the sparse matrix file and assign values on the fly
		            try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(matrixMtx)), StandardCharsets.UTF_8))) {
		                // Skip header lines (usually first 3 lines in Matrix Market format)
		                for (int i = 0; i < 3; i++) {
		                    br.readLine();
		                }
		                String entry;
		                while ((entry = br.readLine()) != null) {
		                    String[] parts = entry.split(" ");
		                    if (parts.length < 3) continue;
		                    int featureIndex = Integer.parseInt(parts[0]) - 1;
		                    int barcodeIndex = Integer.parseInt(parts[1]) - 1;
		                    int count = Integer.parseInt(parts[2]);
		                    // Only record gene expression counts for mapped cells
		                    PathObject cellObj = (barcodeIndex >= 0 && barcodeIndex < numCells) ? cellByIndex[barcodeIndex] : null;
		                    if (cellObj == null) continue;  // cell not in our selection
		                    // Only include certain feature types (e.g., Gene Expression)
		                    if (!"Gene Expression".equals(featureTypes.get(featureIndex))) {
		                        continue; // skip non-gene expression features to save space (optional filters)
		                    }
		                    // Add the transcript count measurement: key format "transcript:GENE"
		                    String geneName = featureNames.get(featureIndex);
		                    MeasurementList meas = cellObj.getMeasurementList();
		                    meas.put("transcript:" + geneName, count);
		                }
		            }
		        } else if(Files.exists(cellFeatureMatrixTarGzFilePath)) {
		        	try (
		        			FileInputStream fis = new FileInputStream(cellFeatureMatrixTarGzFilePath.toFile());
		        			GZIPInputStream gzipIn = new GZIPInputStream(fis);
		        			TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn); 
		        		) {
			        		TarArchiveEntry tarAchEntry;
			        		int numCells = -1;
	    		            PathObject[] cellByIndex = null;
	    		            List<String> featureNames = null;
	    		            List<String> featureTypes = null;
			        		while ((tarAchEntry = tarIn.getNextTarEntry()) != null) {
			        			if (!tarAchEntry.isDirectory()) {
			        				if (tarAchEntry.getName().endsWith("barcodes.tsv.gz")) {
			        					List<String> barcodes = new ArrayList<>();
			        		            try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(tarIn), StandardCharsets.UTF_8))) {
			        		                String line;
			        		                while ((line = br.readLine()) != null) {
			        		                    barcodes.add(line.trim());
			        		                }
			        		            }
			        		            // Map barcode index to PathObject for quick access (or null if not present in cellIdToObject)
			        		            numCells = barcodes.size();
			        		            cellByIndex = new PathObject[numCells];
			        		            for (int i = 0; i < numCells; i++) {
			        		                String cellId = barcodes.get(i);
			        		                cellByIndex[i] = cellIdToObject.get(cellId); // may be null if cell not mapped (e.g. filtered out)
			        		            }
			        				} else if (tarAchEntry.getName().endsWith("features.tsv.gz")) {
			        					featureNames = new ArrayList<>();
			        		            featureTypes = new ArrayList<>();
			        		            try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(tarIn), StandardCharsets.UTF_8))) {
			        		                String line;
			        		                while ((line = br.readLine()) != null) {
			        		                    String[] cols = line.split("\t");
			        		                    if (cols.length >= 3) {
			        		                        featureNames.add(cols[1]);       // gene name
			        		                        featureTypes.add(cols[2]);       // feature type (e.g. "Gene Expression", "Negative Control", etc.)
			        		                    }
			        		                }
			        		            }
			        				} else if (tarAchEntry.getName().endsWith("matrix.mtx.gz") && (numCells > 0) && (cellByIndex != null) && (featureTypes != null) && (featureNames != null)) {
			        					try (BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(tarIn), StandardCharsets.UTF_8))) {
			        		                // Skip header lines (usually first 3 lines in Matrix Market format)
			        		                for (int i = 0; i < 3; i++) {
			        		                    br.readLine();
			        		                }
			        		                String entry;
			        		                while ((entry = br.readLine()) != null) {
			        		                    String[] parts = entry.split(" ");
			        		                    if (parts.length < 3) continue;
			        		                    int featureIndex = Integer.parseInt(parts[0]) - 1;
			        		                    int barcodeIndex = Integer.parseInt(parts[1]) - 1;
			        		                    int count = Integer.parseInt(parts[2]);
			        		                    // Only record gene expression counts for mapped cells
			        		                    PathObject cellObj = (barcodeIndex >= 0 && barcodeIndex < numCells) ? cellByIndex[barcodeIndex] : null;
			        		                    if (cellObj == null) continue;  // cell not in our selection
			        		                    // Only include certain feature types (e.g., Gene Expression)
			        		                    if (!"Gene Expression".equals(featureTypes.get(featureIndex))) {
			        		                        continue; // skip non-gene expression features to save space (optional filters)
			        		                    }
			        		                    // Add the transcript count measurement: key format "transcript:GENE"
			        		                    String geneName = featureNames.get(featureIndex);
			        		                    MeasurementList meas = cellObj.getMeasurementList();
			        		                    meas.put("transcript:" + geneName, count);
			        		                }
			        		            }
			        				}
			        			}
			        		}
			        	}
//			        } else {
		            // No feature matrix found
		            throw new IOException("Feature matrix data not found (expected cell_feature_matrix files or H5).");
		        }
		        
		        // Optionally remove cells that got no label (unmatched cells)
		        if (params.getBooleanParameterValue("removeUnlabeledCells")) {
		            for (PathObject cell : cellObjectList) {
		                if (cell.getPathClass() == null) {
		                    // Remove cell from its parent annotation in hierarchy
		                    PathObject parent = cell.getParent();
		                    if (parent != null) {
		                        parent.removeChildObject(cell);
		                    }
		                }
		            }
		        }
		        
		        // Clear selection (to avoid lingering selection in the UI)
		        hierarchy.getSelectionModel().setSelectedObject(null);
		    } catch (Exception e) {
		        // Log and report any error
		        lastResults = "Error: " + e.getMessage();
		        logger.error(lastResults, e);
		        if (QuPathGUI.getInstance() != null) {
		            Dialogs.showErrorMessage("Xenium Import Error", lastResults);
		        }
		        return hierarchy.getRootObject().getChildObjects(); // return current objects without modification
		    } finally {
		        g2d.dispose();
		    }
		    
		    // Return the updated PathObjects (which now include Xenium data)
		    return resultPathObjectList;
		}

		
		@Override
		public String getLastResultsDescription() {
			return lastResults;
		}
	}

	private static void loadNativeLibrary() throws IOException {
		String osName = System.getProperty("os.name").toLowerCase();
		
		String libResourcePath = "/native/libhdf5_java.so";
		
		if (osName.contains("nix") || osName.contains("nux")) {
			libResourcePath = "/native/linux/libhdf5_java.so";
		} else {
			throw new UnsupportedOperationException("Unsupported OS: "+ osName);
		}
		
		InputStream in = XeniumAnnotation_buggy.class.getResourceAsStream(libResourcePath);
		if (in == null) 
			throw new FileNotFoundException("Native library not found: "+libResourcePath);
		
		File temp = File.createTempFile("libhdf5_java", ".so");
		temp.deleteOnExit();
		
		try (OutputStream out = new FileOutputStream(temp)) {
			byte[] buf = new byte[4096];
			int len;
			while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
		}
		System.load(temp.getAbsolutePath());
	}
	
	@Override
	protected void preprocess(TaskRunner taskRunner, ImageData<BufferedImage> imageData) {
		if(params.getStringParameterValue("xeniumDir").isBlank()) {
		
			File xnumDir = FileChoosers.promptForDirectory("Xenium directory", new File(xnumAntnXnumFldrProp.get()));
			
			if (xnumDir != null) {
				xnumAntnXnumFldrProp.set(xnumDir.toString());
			}
			else {
				lastResults =  "No Xenium directory is selected!";
				if (QuPathGUI.getInstance() != null) Dialogs.showWarningNotification("Warning", lastResults);
				logger.warn(lastResults);
			}
		}
		else {
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
