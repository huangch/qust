package qupath.ext.qust;

import ai.djl.Device;
import ai.djl.util.cuda.CudaUtils;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BestGPUSelector {
   public static class GPUInfo {
       public final Device device;
       public final int gpuId;
       public final long totalMemory;
       public final long usedMemory;
       public final long freeMemory;
       public final double memoryUtilization;
       public final String computeCapability;
       public final double score;
       public GPUInfo(Device device, int gpuId, MemoryUsage memory, String computeCapability) {
           this.device = device;
           this.gpuId = gpuId;
           this.totalMemory = memory.getMax();
           this.usedMemory = memory.getUsed();
           this.freeMemory = totalMemory - usedMemory;
           this.memoryUtilization = (double) usedMemory / totalMemory * 100.0;
           this.computeCapability = computeCapability;
           // Calculate composite score (lower is better)
           this.score = calculateScore();
       }
       private double calculateScore() {
           // Score factors (lower = better):
           // 1. Memory utilization (0-100) - weight: 0.6
           // 2. Inverse of free memory (prefer more free memory) - weight: 0.3
           // 3. Inverse of compute capability (prefer newer GPUs) - weight: 0.1
           double memoryScore = memoryUtilization; // 0-100
           double freeMemoryScore = totalMemory > 0 ? (1.0 - (double) freeMemory / totalMemory) * 100 : 100;
           double computeScore = getComputeCapabilityScore();
           return (memoryScore * 0.6) + (freeMemoryScore * 0.3) + (computeScore * 0.1);
       }
       private double getComputeCapabilityScore() {
           try {
               // Parse compute capability (e.g., "8.6" -> 8.6)
               double capability = Double.parseDouble(computeCapability);
               // Convert to score (lower is better, so invert)
               return Math.max(0, 10.0 - capability) * 10; // Scale to 0-100 range
           } catch (Exception e) {
               return 50.0; // Default score if parsing fails
           }
       }
       @Override
       public String toString() {
           return String.format("GPU %d: %.1f%% memory used, %s free, compute %s, score %.1f",
               gpuId, memoryUtilization, formatBytes(freeMemory), computeCapability, score);
       }
   }
   /**
    * Get the best available GPU based on memory usage and compute capability
    *
    * @param maxMemoryUtilization Maximum acceptable memory utilization (0-100)
    * @param minFreeMemoryGB Minimum required free memory in GB
    * @return Best GPU device, or CPU if no suitable GPU found
    */
   public static Device getBestGPU(double maxMemoryUtilization, double minFreeMemoryGB) {
       List<GPUInfo> gpuInfos = getAllGPUInfo();
       if (gpuInfos.isEmpty()) {
           System.out.println("No GPUs available, using CPU");
           return Device.cpu();
       }
       System.out.println("\n=== GPU Analysis ===");
       gpuInfos.forEach(System.out::println);
       // Filter GPUs that meet requirements
       List<GPUInfo> suitableGPUs = gpuInfos.stream()
           .filter(gpu -> gpu.memoryUtilization <= maxMemoryUtilization)
           .filter(gpu -> gpu.freeMemory >= (long)(minFreeMemoryGB * 1024 * 1024 * 1024))
           .sorted(Comparator.comparingDouble(gpu -> gpu.score))
           .toList();
       if (suitableGPUs.isEmpty()) {
           System.out.println("No GPUs meet requirements, using best available GPU");
           // Use GPU with lowest score even if it doesn't meet requirements
           GPUInfo bestGPU = gpuInfos.stream()
               .min(Comparator.comparingDouble(gpu -> gpu.score))
               .orElse(null);
           if (bestGPU != null) {
               System.out.println("Selected: " + bestGPU);
               return bestGPU.device;
           } else {
               return Device.cpu();
           }
       }
       GPUInfo selectedGPU = suitableGPUs.get(0);
       System.out.println("Selected: " + selectedGPU);
       return selectedGPU.device;
   }
   /**
    * Simple method - get GPU with most free memory
    */
   public static Device getBestGPUByFreeMemory() {
       List<GPUInfo> gpuInfos = getAllGPUInfo();
       if (gpuInfos.isEmpty()) {
           return Device.cpu();
       }
       GPUInfo bestGPU = gpuInfos.stream()
           .max(Comparator.comparingLong(gpu -> gpu.freeMemory))
           .orElse(null);
       if (bestGPU != null) {
           System.out.println("Selected GPU " + bestGPU.gpuId + " with " +
                            formatBytes(bestGPU.freeMemory) + " free memory");
           return bestGPU.device;
       }
       return Device.cpu();
   }
   /**
    * Simple method - get GPU with lowest memory utilization
    */
   public static Device getBestGPUByUtilization() {
       List<GPUInfo> gpuInfos = getAllGPUInfo();
       if (gpuInfos.isEmpty()) {
           return Device.cpu();
       }
       GPUInfo bestGPU = gpuInfos.stream()
           .min(Comparator.comparingDouble(gpu -> gpu.memoryUtilization))
           .orElse(null);
       if (bestGPU != null) {
           System.out.println("Selected GPU " + bestGPU.gpuId + " with " +
                            String.format("%.1f%% utilization", bestGPU.memoryUtilization));
           return bestGPU.device;
       }
       return Device.cpu();
   }
   /**
    * Get detailed information about all available GPUs
    */
   public static List<GPUInfo> getAllGPUInfo() {
       List<GPUInfo> gpuInfos = new ArrayList<>();
       try {
           int gpuCount = CudaUtils.getGpuCount();
           for (int i = 0; i < gpuCount; i++) {
               try {
                   Device device = Device.gpu(i);
                   MemoryUsage memory = CudaUtils.getGpuMemory(device);
                   String computeCapability = CudaUtils.getComputeCapability(i);
                   if (memory.getMax() > 0) {
                       gpuInfos.add(new GPUInfo(device, i, memory, computeCapability));
                   }
               } catch (Exception e) {
                   System.err.println("Failed to get info for GPU " + i + ": " + e.getMessage());
               }
           }
       } catch (Exception e) {
           System.err.println("Failed to enumerate GPUs: " + e.getMessage());
       }
       return gpuInfos;
   }
   /**
    * Print detailed status of all GPUs
    */
   public static void printAllGPUStatus() {
       System.out.println("\n=== Detailed GPU Status ===");
       List<GPUInfo> gpuInfos = getAllGPUInfo();
       if (gpuInfos.isEmpty()) {
           System.out.println("No GPUs available");
           return;
       }
       System.out.println("CUDA Version: " + CudaUtils.getCudaVersionString());
       System.out.println();
       for (GPUInfo gpu : gpuInfos) {
           System.out.println("GPU " + gpu.gpuId + ":");
           System.out.println("  Device: " + gpu.device);
           System.out.println("  Compute Capability: " + gpu.computeCapability);
           System.out.println("  Total Memory: " + formatBytes(gpu.totalMemory));
           System.out.println("  Used Memory:  " + formatBytes(gpu.usedMemory));
           System.out.println("  Free Memory:  " + formatBytes(gpu.freeMemory));
           System.out.println("  Utilization:  " + String.format("%.1f%%", gpu.memoryUtilization));
           System.out.println("  Score:        " + String.format("%.1f", gpu.score));
           System.out.println();
       }
   }
   private static String formatBytes(long bytes) {
       if (bytes < 1024) return bytes + " B";
       if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
       if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
       return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
   }
}