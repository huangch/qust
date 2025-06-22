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
 * along with QuST. If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.qust;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import hdf.hdf5lib.H5;
import hdf.hdf5lib.HDF5Constants;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjectConnections;
import qupath.lib.objects.PathRootObject;
import qupath.lib.objects.TMACoreObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractDetectionPlugin;
import qupath.lib.plugins.DetectionPluginTools;
import qupath.lib.plugins.ObjectDetector;
import qupath.lib.plugins.TaskRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.projects.Project;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.roi.interfaces.ROI;
//import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.lib.common.GeneralTools;
import qupath.lib.images.ImageData;
import qupath.lib.measurements.MeasurementList;
import qupath.lib.gui.QuPathGUI;

/**
 * Plugins for exporting detection measurements to h5ad.
 * 
 * @author Chao Hui Huang
 *
 */
public class DetectionMeasurementToH5AD extends AbstractDetectionPlugin<BufferedImage> {
	
	private static Logger logger = LoggerFactory.getLogger(DetectionMeasurementToH5AD.class);
	private ParameterList params = null;
	private File outFile = null;
	private List<PathObject> chosenObjects = Collections.synchronizedList(new ArrayList<PathObject>());
	private String allObjects = "All objects";
	private String selectedObjects = "Selected objects";
	private String lastResults = null;
	
	/**
	 * Constructor.
	 * @throws Exception 
	 */
	public DetectionMeasurementToH5AD() throws Exception {
		// Get ImageData
		QuPathGUI qupath = QuPathGUI.getInstance();
		ImageData<BufferedImage> imageData = qupath.getImageData();
		
		if (imageData == null)
			return;
		
		// Get hierarchy
		PathObjectHierarchy hierarchy = imageData.getHierarchy();
     
		String defaultObjects = hierarchy.getSelectionModel().noSelection() ? allObjects : selectedObjects;
		
        params = new ParameterList()
        	.addStringParameter("fileName", "Save to ", "", "Save detection measurement data to the chosen h5ad file")
        	.addChoiceParameter("exportOptions", "Export ", defaultObjects, Arrays.asList(allObjects, selectedObjects), "Choose which objects to export - run a 'Select annotations/detections' command first if needed")
        	.addBooleanParameter("delaunay", "Include Delaunay clustering", false, "Include Delaunay clustering")
			;
	}
	
	private static void loadNativeLibrary() throws IOException {
		String osName = System.getProperty("os.name").toLowerCase();
		
		String libResourcePath = "/native/libhdf5_java.so";
		
		if (osName.contains("nix") || osName.contains("nux")) {
			libResourcePath = "/native/linux/libhdf5_java.so";
		} else {
			throw new UnsupportedOperationException("Unsupported OS: "+ osName);
		}
		
		InputStream in = DetectionMeasurementToH5AD.class.getResourceAsStream(libResourcePath);
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
	
	private static void H5ADwrite_attribute_str(long field_id, String attribute_name, String attribute_value) throws UnsupportedEncodingException {
        byte[] attribute_value_raw = attribute_value.getBytes("UTF-8");
        long attribute_type_id = H5.H5Tcopy(HDF5Constants.H5T_C_S1);
        H5.H5Tset_size(attribute_type_id, attribute_value_raw.length);
        H5.H5Tset_strpad(attribute_type_id, HDF5Constants.H5T_STR_NULLTERM);
        long attribute_space_id = H5.H5Screate(HDF5Constants.H5S_SCALAR);
       	long attribute_id = H5.H5Acreate(field_id, attribute_name, attribute_type_id, attribute_space_id,
       			HDF5Constants.H5P_DEFAULT, HDF5Constants.H5P_DEFAULT);
       	H5.H5Awrite(attribute_id, attribute_type_id, attribute_value_raw);
       	H5.H5Aclose(attribute_id);
       	H5.H5Sclose(attribute_space_id);
       	H5.H5Tclose(attribute_type_id);
	}
	
	private static void H5ADwrite_attribute_long_array(long field_id, String attribute_name, long[] attribute_value) throws UnsupportedEncodingException {
        long attribute_type_id = H5.H5Tcopy(HDF5Constants.H5T_STD_I64LE);
        long[] attribute_dims = {attribute_value.length};
        long attribute_space_id = H5.H5Screate_simple(1, attribute_dims, null);
        
        long attribute_id = H5.H5Acreate(field_id, attribute_name, attribute_type_id, attribute_space_id, 
        		HDF5Constants.H5P_DEFAULT, HDF5Constants.H5P_DEFAULT);
		
        H5.H5Awrite(attribute_id, attribute_type_id, attribute_value);
        
       	H5.H5Aclose(attribute_id);
       	H5.H5Sclose(attribute_space_id);
       	H5.H5Tclose(attribute_type_id);
	}
	
	private static void H5ADwrite_dataset_float_array(long field_id, String dataset_name, float[] data) throws UnsupportedEncodingException {
		long[] data_dims = {data.length};
		long data_space_id = H5.H5Screate_simple(1, data_dims, null);
		long data_dataset_id = 0;
		
		data_dataset_id = H5.H5Dcreate(field_id, dataset_name, HDF5Constants.H5T_IEEE_F32LE, data_space_id, 
				HDF5Constants.H5P_DEFAULT, HDF5Constants.H5P_DEFAULT, HDF5Constants.H5P_DEFAULT);
		H5.H5Dwrite(data_dataset_id, HDF5Constants.H5T_IEEE_F32LE, HDF5Constants.H5S_ALL,
				HDF5Constants.H5S_ALL, HDF5Constants.H5P_DEFAULT, data);
		
	    H5.H5Sclose(data_space_id);
	    H5.H5Dclose(data_dataset_id);
	}
	
	private static void H5ADwrite_dataset_int_array(long field_id, String dataset_name, int[] data) throws UnsupportedEncodingException {
		long[] data_dims = {data.length};
		long data_space_id = H5.H5Screate_simple(1, data_dims, null);
		long data_dataset_id = 0;
		
		data_dataset_id = H5.H5Dcreate(field_id, dataset_name, HDF5Constants.H5T_STD_I32LE, data_space_id, 
				HDF5Constants.H5P_DEFAULT, HDF5Constants.H5P_DEFAULT, HDF5Constants.H5P_DEFAULT);
		H5.H5Dwrite(data_dataset_id, HDF5Constants.H5T_STD_I32LE, HDF5Constants.H5S_ALL,
				HDF5Constants.H5S_ALL, HDF5Constants.H5P_DEFAULT, data);
		
	    H5.H5Sclose(data_space_id);
	    H5.H5Dclose(data_dataset_id);
	}
	
	
	/**
	 * Run the path detection object measurement H5AD export command.
	 * @param outFile 
	 * @return success
	 * @throws IOException 
	 */
	private void writeH5AD(String outFilePath, ImageData<?> imageData) throws RuntimeException, IOException {
		List<String> measIdList = chosenObjects.get(0).getMeasurementList().getMeasurementNames();

		PathObjectConnections connections = (PathObjectConnections) imageData.getProperty("OBJECT_CONNECTIONS");
           
		int n_obs = chosenObjects.size(); // Number of cells
		int n_var = measIdList.size(); // Number of genes
		
		int obs_idx_cnt = 0; // Counting length for cell_id strings
		int obs_cls_cnt = 0; // Counting length for cls_id strings
	    
		int var_idx_cnt = 0; // Fixed length for meas_id strings
		Map<String, Integer> cell_id_map = new HashMap<>();
		
		for (int i = 0; i < n_obs; i++) {
			PathObject o = chosenObjects.get(i);
			
			
			String cell_id = ((o.getName() != null && !o.getName().isBlank()))? o.getName(): o.getID().toString();
					
			cell_id_map.put(cell_id, i);
			
			int idx_l = cell_id.length();
			if (obs_idx_cnt < idx_l) obs_idx_cnt = idx_l;

			int cls_l = o.getPathClass().toString().length();
			if (obs_cls_cnt < cls_l) obs_cls_cnt = cls_l;
		}

		for (int i = 0; i < n_var; i++) {
           int l = measIdList.get(i).length();
           if (var_idx_cnt < l) var_idx_cnt = l;
		}

		final int obs_idx_len = obs_idx_cnt; // Fixed length for gene_id strings
		final int obs_cls_len = obs_cls_cnt; // Fixed length for gene_id strings
		final int var_idx_len = var_idx_cnt; // Fixed length for gene_id strings
        final int obs_cx_len = 4;
        final int obs_cy_len = 4;
		final int chunkSize = 64;        	 // Chunk size for writing 'X'
		final int compressionlevel = 6;
		long file_id = -1;
		
		try {
			// Create a new HDF5 file
	        file_id = H5.H5Fcreate(outFilePath, HDF5Constants.H5F_ACC_TRUNC, 
	        		HDF5Constants.H5P_DEFAULT, HDF5Constants.H5P_DEFAULT);
	           
	        // Create a fixed-length string datatype
	        long obs_idx_type_id = H5.H5Tcopy(HDF5Constants.H5T_C_S1);
	        H5.H5Tset_size(obs_idx_type_id, obs_idx_len);
	        H5.H5Tset_strpad(obs_idx_type_id, HDF5Constants.H5T_STR_NULLPAD);
	           
	        // Create a fixed-length string datatype
	        long obs_cls_type_id = H5.H5Tcopy(HDF5Constants.H5T_C_S1);
	        H5.H5Tset_size(obs_cls_type_id, obs_cls_len);
	        H5.H5Tset_strpad(obs_cls_type_id, HDF5Constants.H5T_STR_NULLPAD);		        
	        
	        long obs_cx_type_id = HDF5Constants.H5T_IEEE_F32LE;
	        long obs_cy_type_id = HDF5Constants.H5T_IEEE_F32LE;
	        
	        // ----------------------------
		    // Create a compound datatype with qupath members
	        // ----------------------------
		    long obs_type_id = H5.H5Tcreate(HDF5Constants.H5T_COMPOUND, obs_idx_len+obs_cls_len+obs_cx_len+obs_cy_len);
	        H5.H5Tinsert(obs_type_id, "index", 0, obs_idx_type_id);
	        H5.H5Tinsert(obs_type_id, "classification", obs_idx_len, obs_cls_type_id);
	        H5.H5Tinsert(obs_type_id, "centroid_x", obs_idx_len+obs_cls_len, obs_cx_type_id);
	        H5.H5Tinsert(obs_type_id, "centroid_y", obs_idx_len+obs_cls_len+obs_cx_len, obs_cy_type_id);
	        
	        // Define the dataspace for the dataset
	        long[] obs_dims = {n_obs};
	        long obs_space_id = H5.H5Screate_simple(1, obs_dims, null);
	        
	        // Create the 'obs' dataset
	        long obs_dataset_id = H5.H5Dcreate(file_id, "/obs", obs_type_id,
	        		obs_space_id, HDF5Constants.H5P_DEFAULT,
	        		HDF5Constants.H5P_DEFAULT, HDF5Constants.H5P_DEFAULT);
	        
	        // Prepare and write data for 'obs'
	        ByteBuffer obs_buffer = ByteBuffer.allocate(n_obs * (obs_idx_len+obs_cls_len+obs_cx_len+obs_cy_len));
	        obs_buffer.order(ByteOrder.nativeOrder());
	        
	        logger.info("Building obs...");
			
	        chosenObjects.stream().forEach(o-> {
	        	byte[] obs_buf_bytes = new byte[obs_idx_len+obs_cls_len+obs_cx_len+obs_cy_len];
	        	
	        	String cell_id = ((o.getName() != null && !o.getName().isBlank()))? o.getName(): o.getID().toString();
				
	        	byte[] cell_idx_raw = cell_id.getBytes(StandardCharsets.UTF_8);
	            System.arraycopy(cell_idx_raw, 0, obs_buf_bytes, 0, Math.min(cell_idx_raw.length, obs_idx_len));
	            
	        	String cell_cls = o.getPathClass().toString();
	        	byte[] cell_cls_raw = cell_cls.getBytes(StandardCharsets.UTF_8);
	            System.arraycopy(cell_cls_raw, 0, obs_buf_bytes, obs_idx_len, Math.min(cell_cls_raw.length, obs_cls_len));
	            
	            ByteBuffer cell_cx_buf = ByteBuffer.allocate(obs_cx_len).order(ByteOrder.LITTLE_ENDIAN);
	            cell_cx_buf.putFloat((float)o.getROI().getCentroidX());
	            byte[] cell_cx_raw = cell_cx_buf.array();
	            System.arraycopy(cell_cx_raw, 0, obs_buf_bytes, obs_idx_len+obs_cls_len, Math.min(cell_cx_raw.length, obs_cx_len));
	            
	            ByteBuffer cell_cy_buf = ByteBuffer.allocate(obs_cy_len).order(ByteOrder.LITTLE_ENDIAN);
	            cell_cy_buf.putFloat((float)o.getROI().getCentroidY());
	            byte[] cell_cy_raw = cell_cy_buf.array();
	            System.arraycopy(cell_cy_raw, 0, obs_buf_bytes, obs_idx_len+obs_cls_len+obs_cx_len, Math.min(cell_cy_raw.length, obs_cy_len));
	            	            
	            obs_buffer.put(obs_buf_bytes);
	        });
	        
	        obs_buffer.rewind();
	        H5.H5Dwrite(obs_dataset_id, obs_type_id, HDF5Constants.H5S_ALL,
	        		HDF5Constants.H5S_ALL, HDF5Constants.H5P_DEFAULT, obs_buffer.array());
	       
	        // Close all HDF5 resources
	        H5.H5Dclose(obs_dataset_id);
	        H5.H5Sclose(obs_space_id);
	        H5.H5Tclose(obs_type_id);
	        H5.H5Tclose(obs_idx_type_id);	
	        H5.H5Tclose(obs_cls_type_id);	
	       
	        
	        if (params.getBooleanParameterValue("delaunay")) {
		        // neighbors is a CSR compatible sparse matrix
		        logger.info("Building obsp...");
		       
		        List<Float> csr_data = new ArrayList<>();
		        List<Integer> csr_indices = new ArrayList<>();
	            int[] csr_indptr_ary = new int[n_obs+1]; 
	            int nnz = 0;
	            
		        for (int i = 0; i < n_obs; i++) {
		        	csr_indptr_ary[i] = nnz;
		        	
		        	PathObject o = chosenObjects.get(i);
		        	List<PathObject> neighbors = connections.getConnections(o);
		        		
					for(PathObject n: neighbors) {
			        	String cell_id = ((n.getName() != null && !n.getName().isBlank()))? n.getName(): n.getID().toString();
						int cell_id_idx = cell_id_map.get(cell_id);
						csr_indices.add(cell_id_idx);
						csr_data.add(1.0F);
						nnz ++;
					}
		        }
		        
		        csr_indptr_ary[n_obs] = nnz;
	           
		        if(nnz > 0) {
			        // Create a group datatype with a single member: 'neighbors'
		 	       
			        long obsp_group_id = H5.H5Gcreate(file_id, "/obsp", HDF5Constants.H5P_DEFAULT,
			        		HDF5Constants.H5P_DEFAULT, HDF5Constants.H5P_DEFAULT);
		           	
		           	H5ADwrite_attribute_str(obsp_group_id, "encoding-type", "dict");
		           	H5ADwrite_attribute_str(obsp_group_id, "encoding-version", "0.1.0");
		        	
			        long obsp_neighbors_group_id = H5.H5Gcreate(obsp_group_id, "delaunay_clusters", HDF5Constants.H5P_DEFAULT, 
			        		HDF5Constants.H5P_DEFAULT, HDF5Constants.H5P_DEFAULT);	           
	
		           	H5ADwrite_attribute_str(obsp_neighbors_group_id, "encoding-type", "csr_matrix");
		           	H5ADwrite_attribute_str(obsp_neighbors_group_id, "encoding-version", "0.1.0");
		        	
			        List<Long> csr_shape = Arrays.asList((long)n_obs, (long)n_obs);
			        long[] csr_shape_ary = new long[csr_shape.size()];
			        for(int i = 0; i < csr_shape.size(); i ++) csr_shape_ary[i] = csr_shape.get(i);
			        
		           	H5ADwrite_attribute_long_array(obsp_neighbors_group_id, "shape", csr_shape_ary);
		        	
			        // writing csr_data to dataset
			       
			        float[] csr_data_ary = new float[csr_data.size()];
			        for(int i = 0; i < csr_data.size(); i ++) csr_data_ary[i] = csr_data.get(i);
			        
			        H5ADwrite_dataset_float_array(obsp_neighbors_group_id, "data", csr_data_ary);
			        
			        // writing csr_indices to dataset
			       
			        int[] csr_indices_ary = new int[csr_indices.size()];
			        for(int i = 0; i < csr_indices.size(); i ++) csr_indices_ary[i] = csr_indices.get(i);
			        
			        H5ADwrite_dataset_int_array(obsp_neighbors_group_id, "indices", csr_indices_ary);
			        
			        // writing csr_indptr to dataset
			                   
			        H5ADwrite_dataset_int_array(obsp_neighbors_group_id, "indptr", csr_indptr_ary);
			  
		           	H5.H5Gclose(obsp_neighbors_group_id);
		           	H5.H5Gclose(obsp_group_id);
		        }
	        }
	        
	        logger.info("Building var...");
	       	// Create a fixed-length string datatype
	       	long var_str_type_id = H5.H5Tcopy(HDF5Constants.H5T_C_S1);
	       	H5.H5Tset_size(var_str_type_id, var_idx_len);
	       	H5.H5Tset_strpad(var_str_type_id, HDF5Constants.H5T_STR_NULLPAD);
		   
	       	// Create a compound datatype with a single member: 'index'
	       	long var_type_id = H5.H5Tcreate(HDF5Constants.H5T_COMPOUND, var_idx_len);
	       	H5.H5Tinsert(var_type_id, "index", 0, var_str_type_id);
		   
	       	// Define the dataspace for the dataset
	       	long[] var_dims = {n_var};
	       	long var_space_id = H5.H5Screate_simple(1, var_dims, null);
		   
	       	// Create the 'var' dataset
	       	long var_dataset_id = H5.H5Dcreate(file_id, "/var", var_type_id,
	       			var_space_id, HDF5Constants.H5P_DEFAULT,
				   	HDF5Constants.H5P_DEFAULT, HDF5Constants.H5P_DEFAULT);
		   
	       	// Prepare and write data for 'var'
	       	ByteBuffer var_buffer = ByteBuffer.allocate(n_var * var_idx_len);
	       	var_buffer.order(ByteOrder.nativeOrder());
	       	
	       	measIdList.stream().forEach(m -> {
	       		String gene_id = m.replace("\u03BC", "u").replace("\u00B5", "u");
	       		byte[] gene_id_bytes = new byte[var_idx_len];
	       		byte[] gene_id_raw = gene_id.getBytes(StandardCharsets.UTF_8);
	       		System.arraycopy(gene_id_raw, 0, gene_id_bytes, 0, Math.min(gene_id_raw.length, var_idx_len));
	       		
	       		var_buffer.put(gene_id_bytes);
	       	});
	       	
	       	var_buffer.rewind();
	       	H5.H5Dwrite(var_dataset_id, var_type_id, HDF5Constants.H5S_ALL,
	                   	HDF5Constants.H5S_ALL, HDF5Constants.H5P_DEFAULT, var_buffer.array());
	       
	       	// Close all HDF5 resources
	       	H5.H5Dclose(var_dataset_id);
	       	H5.H5Sclose(var_space_id);
	       	H5.H5Tclose(var_type_id);
	       	H5.H5Tclose(var_str_type_id);
	           
	        // ----------------------------
	       	// Create 'X' dataset (chunked 2D float array)
	       	// ----------------------------
	       	long[] x_dims = {n_obs, n_var};
	       	long[] chunk_dims = {chunkSize, n_var}; // Chunk along rows
	       	long x_space_id = H5.H5Screate_simple(2, x_dims, null);
	       	long plist_id = H5.H5Pcreate(HDF5Constants.H5P_DATASET_CREATE);
	       	H5.H5Pset_chunk(plist_id, 2, chunk_dims);
	       	H5.H5Pset_deflate(plist_id, compressionlevel);
	       	long x_dataset_id = H5.H5Dcreate(file_id, "/X", HDF5Constants.H5T_NATIVE_FLOAT,
	       			x_space_id, HDF5Constants.H5P_DEFAULT,
	       			plist_id, HDF5Constants.H5P_DEFAULT);
	       
	       	int progress_old = -1;
	       	// Write data to 'X' in chunks
	       	for (int i = 0; i < n_obs; i += chunkSize) {
	       		int progress_new = (int)(10*(float)i/(float)(n_obs-1));
	       		
	       		if(progress_new != progress_old) {
	       			progress_old = progress_new;
	       			logger.info("Building X...%d%%".formatted(10*progress_new));
	       		}
	       		
	       		int rowsInChunk = Math.min(chunkSize, n_obs - i);
	       		float[] x_chunk = new float[rowsInChunk * n_var];
	
	       		int final_i = i;
	       		IntStream.range(0, rowsInChunk).parallel().forEach(r -> {
	       			MeasurementList measValList = chosenObjects.get(final_i+r).getMeasurementList();
	        	   
	       			IntStream.range(0, n_var).parallel().forEach(c -> {
	       				x_chunk[r * n_var + c] = (float) measValList.get(measIdList.get(c));
	       			});
	    		});
	       		 
	           	// Define hyperslab in the file
	           	long[] start = {i, 0};
	           	long[] count = {rowsInChunk, n_var};
	           	long file_space = H5.H5Dget_space(x_dataset_id);
	           	H5.H5Sselect_hyperslab(file_space, HDF5Constants.H5S_SELECT_SET, start, null, count, null);
	           	// Define memory space
	           	long mem_space = H5.H5Screate_simple(2, count, null);
	           	// Write the chunk
	           	H5.H5Dwrite(x_dataset_id, HDF5Constants.H5T_NATIVE_FLOAT, mem_space, file_space,
	           			HDF5Constants.H5P_DEFAULT, x_chunk);
	           	// Close memory and file spaces
	           	H5.H5Sclose(mem_space);
	           	H5.H5Sclose(file_space);
	       	}
	       	
	       	logger.info("Building X...100%");
	       	
	       	// Close 'X' resources
	       		H5.H5Dclose(x_dataset_id);
	   			H5.H5Sclose(x_space_id);
	   			H5.H5Pclose(plist_id);
	   
	   			// Close file
			H5.H5Fclose(file_id);
			
			logger.info("Path detection object measurement dataset has been saved to %s.".formatted(outFilePath));
		
		} catch (Exception e) {
			e.printStackTrace();
           	logger.error(e.toString());
//           	Dialogs.showErrorMessage("Error", e.getLocalizedMessage());
           	if (file_id >= 0) {
               	try {
                   	H5.H5Fclose(file_id);
                   	
                    // Prompt for where to save
            		File out_file = new File(outFilePath);
                    
            		if (out_file.exists()) {
            			out_file.delete();
            		}
            		
               	} catch (Exception ignore) {
//               		Dialogs.showErrorMessage("Error", e.getLocalizedMessage());
                   	e.printStackTrace();
                   	logger.error(e.getLocalizedMessage());
               	}
           	}
        }	
	}
	
	@Override
	protected void preprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		String fileName = params.getStringParameterValue("fileName");

		if (fileName.isBlank()) {
			QuPathGUI qupath = QuPathGUI.getInstance();
			
			// Get default name & output directory
			Project<BufferedImage> project = qupath.getProject();
			String defaultName = imageData.getServer().getMetadata().getName();
			
			if (project != null) {
				ProjectImageEntry<BufferedImage> entry = project.getEntry(imageData);
				if (entry != null)
					defaultName = entry.getImageName();
			}
			
			defaultName = GeneralTools.stripExtension(defaultName);
			File defaultDirectory = project == null || project.getPath() == null ? null : project.getPath().toFile();
			while (defaultDirectory != null && !defaultDirectory.isDirectory())
				defaultDirectory = defaultDirectory.getParentFile();
			File defaultFile = new File(defaultDirectory, defaultName);
			
			outFile = FileChoosers.promptToSaveFile("Export to file", defaultFile,
					FileChoosers.createExtensionFilter("AnnData file", ".h5ad"));
			
			Map <String, String> newParam = Map.of("fileName", outFile.toString());
			ParameterList.updateParameterList(params, newParam, Locale.getDefault());
		}
		else {
			outFile = new File(fileName);
		}		
	}
	
	@Override
	protected void postprocess(final TaskRunner taskRunner, final ImageData<BufferedImage> imageData) {
		// Export
		try {
			if(chosenObjects.size() > 0) {
				// If user cancels
				if (outFile == null)
					return;
				else if (outFile.exists()) 
					outFile.delete();
				
				loadNativeLibrary();
				writeH5AD(outFile.getAbsolutePath(), imageData);
			}
		} catch (IOException e) {
			e.printStackTrace();
			logger.error(e.getLocalizedMessage());;
//			Dialogs.showErrorMessage("Error", e.getLocalizedMessage());
		}
	}
	
	@Override
	public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
		return params;
	}

	@Override
	public String getName() {
		return "Plugins for exporting detection measurements to h5ad";
	}

	@Override
	public String getLastResultsDescription() {
		return lastResults;
	}

	@Override
	public String getDescription() {
		return "Plugins for exporting detection measurements to h5ad";
	}

	class AnnotationLoader implements ObjectDetector<BufferedImage> {
		@Override
		public Collection<PathObject> runDetection(final ImageData<BufferedImage> imageData, final ParameterList params, final ROI pathROI) throws IOException {
			// Get hierarchy
			PathObjectHierarchy hierarchy = imageData.getHierarchy();
			Collection<PathObject> toProcess;
			
			String comboChoice = (String)params.getChoiceParameterValue("exportOptions");
			if (comboChoice.equals(selectedObjects)) {
				if (hierarchy.getSelectionModel().noSelection()) {
//					Dialogs.showErrorMessage("No selection", "No selection detected!");
					return new ArrayList<PathObject>(hierarchy.getRootObject().getChildObjects());
				}
				toProcess = hierarchy.getSelectionModel().getSelectedObjects();
			} else
				toProcess = hierarchy.getObjects(null, null);
			
			// Remove PathRootObject
			toProcess = toProcess.stream().filter(e -> !e.isRootObject()).toList();

			toProcess.stream().forEach(p -> {
				if (p.isAnnotation() && p.hasChildObjects()) {
					chosenObjects.addAll(p.getChildObjects().stream().filter(e -> e.isDetection()).collect(Collectors.toList()));
				}	
				else if(p.isDetection()) {
					chosenObjects.add(p);
				}	
			});
			
			if (Thread.currentThread().isInterrupted()) {
//				Dialogs.showErrorMessage("Warning", "Interrupted!");
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
		// Temporarily disabled so as to avoid asking annoying questions when run repeatedly
		List<Class<? extends PathObject>> list = new ArrayList<>();
//		list.add(PathAnnotationObject.class);
		list.add(TMACoreObject.class);
		list.add(PathRootObject.class);
		return list;		
	}
}
