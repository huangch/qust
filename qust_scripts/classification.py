import glob
import pandas as pd
import os
import time
from PIL import Image
from torch.utils.data import DataLoader
from torch.utils.data import Dataset
import torchvision.transforms as T
import torch
import argparse
import histomicstk as htk
import json
import numpy as np
from multiprocessing import Pool
import random
import tqdm
import functools

parser = argparse.ArgumentParser()
parser.add_argument('action', type=str, help='action')
parser.add_argument('result_file', type=str, help='result_file')
parser.add_argument('--model_file', help='model file', required=False)
parser.add_argument('--image_path', help='image path', required=False)
parser.add_argument('--image_format', type=str, default='png', help='image format', required=False)
parser.add_argument('--batch_size', type=int, default=128, help='batch size', required=False)
parser.add_argument('--normalizer_w', nargs='+', type=float, help='normalizer w', required=False)
opt = parser.parse_args()
# print(opt)

print('NVIDIA_VISIBLE_DEVICES: '+os.environ["NVIDIA_VISIBLE_DEVICES"] if "NVIDIA_VISIBLE_DEVICES" in os.environ.keys() else "NVIDIA_VISIBLE_DEVICES is not set")
print('CUDA_VISIBLE_DEVICES: '+os.environ["CUDA_VISIBLE_DEVICES"] if "CUDA_VISIBLE_DEVICES" in os.environ.keys() else "CUDA_VISIBLE_DEVICES is not set")

# Set device
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

EPSILON = 1e-8
I_0 = 255
RETRY_TIMES = 65535
RETRY_SLEEP = 1

def normalizer_process(W_est, W_def, image_filename):
    img = Image.open(image_filename)
    img = img.convert('RGB')
    img = np.array(img).astype(np.float32)
    img = htk.preprocessing.color_normalization.deconvolution_based_normalization(img+EPSILON, W_source=W_est, W_target=W_def)
    img = Image.fromarray(img)
    img.save(image_filename)  
    return image_filename

            
def normalize(opt):
    result_json_data = {"success": True}
    
    try:
        # Prepare image data
        image_filename_list = glob.glob(os.path.join(opt.image_path, '*'))
        random.shuffle(image_filename_list)
        sampled_image_filename_list = random.sample(image_filename_list, 1000)
        
        image_size = None
        sampled_image_arraylist = []
        
        for image_filename in sampled_image_filename_list:
            img = Image.open(image_filename)
            img = img.convert('RGB')
            img = np.array(img).astype(np.float32)
        
            assert img.shape[0] == img.shape[1]
            assert image_size == None or image_size == img.shape[0]
            image_size = img.shape[0]
        
            sampled_image_arraylist.append(img.reshape((1, *img.shape)))
    
        sampled_image_arraylist_concat = np.concatenate(sampled_image_arraylist)
        W_est = htk.preprocessing.color_deconvolution.rgb_separate_stains_macenko_pca(sampled_image_arraylist_concat+EPSILON, I_0)

        stain_color_map = htk.preprocessing.color_deconvolution.stain_color_map
        stains = ['eosin', 'hematoxylin', 'null']
        W_def = np.array([stain_color_map[st] for st in stains]).T
        
        partial_normalizer_process = functools.partial(normalizer_process, W_est, W_def)
        
        with Pool() as p:
            list(tqdm.tqdm_gui(p.imap(partial_normalizer_process, image_filename_list), total=len(image_filename_list)))
        
       
    except Exception as e:      # works on python 3.x
        print(repr(e))
        result_json_data['success'] = False
    finally:
        with open(opt.result_file, "w") as fp:
            json.dump(result_json_data , fp)


class SingleCellImageDataset(Dataset):
    def __init__(self, image_data, 
                 image_size, 
                 image_mean, image_std,
                 model_requires_224 = False,
                 # , k=5, m_list=[0,1,2,3,4]
                 ):
        'Initialization'
        
        # dataset_partitions = self.__partition(dataset, k)
        #
        # self.dataset = []
        # for m in m_list:
        #     self.dataset += dataset_partitions[m]


        self.image_data = image_data
        self.image_mean = image_mean 
        self.image_std = image_std
        
        transformObjectList = [
            T.ToTensor(),
            # T.CenterCrop(image_size),
            T.Resize(image_size, antialias=True),
            T.Normalize(image_mean, image_std),     
            # T.RandomRotation(degrees=(0, 90)),
            # T.RandomHorizontalFlip(),
            # T.RandomVerticalFlip(),
            ] if not model_requires_224 else [
            T.ToTensor(),
            # T.CenterCrop(image_size),
            T.Resize(image_size, antialias=True),
            T.Normalize(image_mean, image_std),     
            # T.RandomRotation(degrees=(0, 90)),
            # T.RandomHorizontalFlip(),
            # T.RandomVerticalFlip(),
            T.Resize(224)
            ] 

        self.transform = T.Compose(transformObjectList)


    def __len__(self):
        'Denotes the total number of samples'
        return len(self.image_data)
    
    def __getitem__(self, index):
        'Generates one sample of data'
        # Select sample
        
        imageFile = self.image_data.iloc[index]['image_filepath']
        img = Image.open(imageFile)
        img = img.convert('RGB')
        img = np.array(img).astype(np.float32)
        img /= 255.0
        img = self.transform(img)
        
        return img
    
    # def __partition(self, lst, n):
    #     division = len(lst) / float(n)
    #     return [ lst[int(round(division * i)): int(round(division * (i + 1)))] for i in range(n) ]

def eval(opt):
    result_json_data = {"success": False}
    
    for try_count in range(RETRY_TIMES):
        try:
            device_count = torch.cuda.device_count()
            
            free_mem_size_list = []
            
            for d in range(device_count):
                free_mem_size_list.append(torch.cuda.mem_get_info(d)[0])
            
            gpu_id = np.argmax(np.asarray(free_mem_size_list))
            device = torch.device('cuda:'+str(gpu_id))

            # device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
            
            # Prepare model data
            model_data = torch.load(opt.model_file)
            model_param = model_data['parameters']
            
            # Prepare image data
            image_filename_list = glob.glob(os.path.join(opt.image_path, '*'))
                
            if model_param['normalized']:
                W_est = np.array(opt.normalizer_w).astype(np.float32).reshape(3,3)
            
                stain_color_map = htk.preprocessing.color_deconvolution.stain_color_map
                stains = ['eosin', 'hematoxylin', 'null']
                W_def = np.array([stain_color_map[st] for st in stains]).T
                
                partial_normalizer_process = functools.partial(normalizer_process, W_est, W_def)
        
                with Pool() as p:
                    p.map(partial_normalizer_process, image_filename_list)
               
            image_filepath_list = []
            image_uuid_list = []
            
            for i in range(len(image_filename_list)):
                image_filepath_list.append(os.path.join(opt.image_path, str(i)+"."+opt.image_format))
                image_uuid_list.append(i)
                            
            image_df = pd.DataFrame({'image_filepath': image_filepath_list, 'Object ID':image_uuid_list})
            image_df.set_index('Object ID', inplace=True)
            image_df.sort_index(inplace=True)
            
            model_requires_224 = False

            if 'model' not in model_param.keys() or model_param['model'] == 'resnet50':
                from torchvision.models import resnet50
                model = resnet50(num_classes=model_param['n_classes'])
                
                     
                     
            elif model_param['model'] == 'resnet18':
                from torchvision.models import resnet18
                model = resnet18(num_classes = model_param['n_classes'])
                
            elif model_param['model'] == 'resnet34':
                from torchvision.models import resnet34
                model = resnet34(num_classes = model_param['n_classes'])
                
            elif model_param['model'] == 'resnet50':
                from torchvision.models import resnet50
                model = resnet50(num_classes = model_param['n_classes'])
        
            elif model_param['model'] == 'resnet101':
                from torchvision.models import resnet101
                model = resnet101(num_classes = model_param['n_classes'])
           
            elif model_param['model'] == 'resnet152':
                from torchvision.models import resnet152
                model = resnet152(num_classes = model_param['n_classes'])
           
            elif model_param['model'] == 'wide_resnet50_2':
                from torchvision.models import wide_resnet50_2
                model = wide_resnet50_2(num_classes = model_param['n_classes'])
           
            elif model_param['model'] == 'wide_resnet101_2':
                from torchvision.models import wide_resnet101_2
                model = wide_resnet101_2(num_classes = model_param['n_classes'])
                        
            elif model_param['model'] == 'resnext50_32x4d':
                from torchvision.models import resnext50_32x4d
                model = resnext50_32x4d(num_classes = model_param['n_classes'])
                
            elif model_param['model'] == 'resnext101_32x8d':
                from torchvision.models import resnext101_32x8d
                model = resnext101_32x8d(num_classes = model_param['n_classes'])
                
            elif model_param['model'] == 'resnext101_64x4d':
                from torchvision.models import resnext101_64x4d
                model = resnext101_64x4d(num_classes = model_param['n_classes'])
        
            elif model_param['model'] == 'densenet121':
                from torchvision.models import densenet121
                model = densenet121(num_classes = model_param['n_classes'])
        
            elif model_param['model'] == 'densenet161':
                from torchvision.models import densenet161
                model = densenet161(num_classes = model_param['n_classes'])
        
            elif model_param['model'] == 'densenet169':
                from torchvision.models import densenet169
                model = densenet169(num_classes = model_param['n_classes'])
        
            elif model_param['model'] == 'densenet201':
                from torchvision.models import densenet201
                model = densenet201(num_classes = model_param['n_classes'])
                
            elif model_param['model'] == 'vit_b_16':
                from torchvision.models import vit_b_16
                model = vit_b_16(image_size=model_param['image_size'], num_classes = model_param['n_classes'])
                # model_requires_224 = True     
        
            elif model_param['model'] == 'vit_b_32':
                from torchvision.models import vit_b_32
                model = vit_b_32(image_size=model_param['image_size'], num_classes = model_param['n_classes']) 
                # model_requires_224 = True
                
            elif model_param['model'] == 'vit_l_16':
                from torchvision.models import vit_l_16
                model = vit_l_16(image_size=model_param['image_size'], num_classes = model_param['n_classes']) 
                # model_requires_224 = True
                
            elif model_param['model'] == 'vit_l_32':
                from torchvision.models import vit_l_32
                model = vit_l_32(image_size=model_param['image_size'], num_classes = model_param['n_classes']) 
                # model_requires_224 = True
                
            elif model_param['model'] == 'vit_h_14':
                from torchvision.models import vit_h_14
                model = vit_h_14(image_size=model_param['image_size'], num_classes = model_param['n_classes']) 
                # model_requires_224 = True
                        
            elif model_param['model'] == 'swin_t':
                from torchvision.models import swin_t
                model = swin_t(image_size=model_param['image_size'], num_classes = model_param['n_classes'])
                # model_requires_224 = True     
                
            elif model_param['model'] == 'swin_s':
                from torchvision.models import swin_s
                model = swin_s(image_size=model_param['image_size'], num_classes = model_param['n_classes'])
                # model_requires_224 = True     
        
            elif model_param['model'] == 'swin_b':
                from torchvision.models import swin_b
                model = swin_b(image_size=model_param['image_size'], num_classes = model_param['n_classes']) 
                # model_requires_224 = True     
                
            elif model_param['model'] == 'swin_v2_t':
                from torchvision.models import swin_v2_t
                model = swin_v2_t(image_size=model_param['image_size'], num_classes = model_param['n_classes']) 
                # model_requires_224 = True     
                
            elif model_param['model'] == 'swin_v2_s':
                from torchvision.models import swin_v2_s
                model = swin_v2_s(image_size=model_param['image_size'], num_classes = model_param['n_classes']) 
                # model_requires_224 = True     
                
            elif model_param['model'] == 'swin_v2_b':
                from torchvision.models import swin_v2_b
                model = swin_v2_b(image_size=model_param['image_size'], num_classes = model_param['n_classes']) 
                # model_requires_224 = True     
        
            elif model_param['model'] == 'vgg11':
                from torchvision.models import vgg11
                model = vgg11(num_classes = model_param['n_classes']) 
                
            elif model_param['model'] == 'vgg11_bn':
                from torchvision.models import vgg11_bn
                model = vgg11_bn(num_classes = model_param['n_classes']) 
                
            elif model_param['model'] == 'vgg13':
                from torchvision.models import vgg13
                model = vgg13(num_classes = model_param['n_classes']) 
                
            elif model_param['model'] == 'vgg13_bn':
                from torchvision.models import vgg13_bn
                model = vgg13_bn(num_classes = model_param['n_classes']) 
                
            elif model_param['model'] == 'vgg16':
                from torchvision.models import vgg16
                model = vgg16(num_classes = model_param['n_classes']) 
                
            elif model_param['model'] == 'vgg16_bn':
                from torchvision.models import vgg16_bn
                model = vgg16_bn(num_classes = model_param['n_classes'])    
                
            elif model_param['model'] == 'vgg19_bn':
                from torchvision.models import vgg19_bn
                model = vgg19_bn(num_classes = model_param['n_classes'])                
                
            elif model_param['model'] == 'maxvit_t':
                from torchvision.models import maxvit_t
                model = maxvit_t(num_classes = model_param['n_classes'])  
                                            
            elif model_param['model'] == 'dev_vit' or model_param['model'] == 'reimp_vit':
                from vit_pytorch import ViT
                model = ViT(
                    image_size= model_param['image_size'],
                    patch_size= model_param['image_size']//8,
                    num_classes = model_param['n_classes'],
                    dim = 1024,
                    depth = 6,
                    heads = 16,
                    mlp_dim = 2048,
                    dropout = 0.1,
                    emb_dropout = 0.1
                )
                
            elif model_param['model'] == 'dev_simplevit':
                from vit_pytorch import SimpleViT
                model = SimpleViT(
                    image_size= model_param['image_size'],
                    patch_size= model_param['image_size']//8,
                    num_classes  = model_param['n_classes'],
                    dim = 1024,
                    depth = 6,
                    heads = 16,
                    mlp_dim = 2048
                )
                
            elif model_param['model'] == 'dev_deepvit':
                from vit_pytorch.deepvit import DeepViT
                model = DeepViT(
                    image_size= model_param['image_size'],
                    patch_size= model_param['image_size']//8,
                    num_classes  = model_param['n_classes'],
                    dim = 1024,
                    depth = 6,
                    heads = 16,
                    mlp_dim = 2048,
                    dropout = 0.1,
                    emb_dropout = 0.1
                )
                
            elif model_param['model'] == 'dev_cait':
                from vit_pytorch.cait import CaiT
                model = CaiT(
                    image_size= model_param['image_size'],
                    patch_size= model_param['image_size']//8,
                    num_classes  = model_param['n_classes'],
                    dim = 1024,
                    depth = 12,             # depth of transformer for patch to patch attention only
                    cls_depth = 2,          # depth of cross attention of CLS tokens to patch
                    heads = 16,
                    mlp_dim = 2048,
                    dropout = 0.1,
                    emb_dropout = 0.1,
                    layer_dropout = 0.05    # randomly dropout 5% of the layers
                )
        
            elif model_param['model'] == 'dev_t2tvit':
                from vit_pytorch.t2t import T2TViT
                model = T2TViT(
                    dim = 512,
                    image_size= model_param['image_size'],
                    depth = 5,
                    heads = 8,
                    mlp_dim = 512,
                    num_classes  = model_param['n_classes'],
                    t2t_layers = ((7, 4), (3, 2), (3, 2)) # tuples of the kernel size and stride of each consecutive layers of the initial token to token module
                )
                
            elif model_param['model'] == 'dev_cct':
                from vit_pytorch.cct import CCT
                model = CCT(
                    img_size = (model_param['image_size'], model_param['image_size']),
                    embedding_dim = 384,
                    n_conv_layers = 2,
                    kernel_size = 7,
                    stride = 2,
                    padding = 3,
                    pooling_kernel_size = 3,
                    pooling_stride = 2,
                    pooling_padding = 1,
                    num_layers = 14,
                    num_heads = 6,
                    mlp_ratio = 3.,
                    num_classes  = model_param['n_classes'],
                    positional_embedding = 'learnable', # ['sine', 'learnable', 'none']
                )
                
            elif model_param['model'] == 'dev_cct_14':
                from vit_pytorch.cct import cct_14
                model = cct_14(
                    img_size= model_param['image_size'],
                    n_conv_layers = 1,
                    kernel_size = 7,
                    stride = 2,
                    padding = 3,
                    pooling_kernel_size = 3,
                    pooling_stride = 2,
                    pooling_padding = 1,
                    num_classes  = model_param['n_classes'],
                    positional_embedding = 'learnable', # ['sine', 'learnable', 'none']
                )
                
            elif model_param['model'] == 'dev_crossvit':
                from vit_pytorch.cross_vit import CrossViT
                model = CrossViT(
                    image_size= model_param['image_size'],
                    num_classes  = model_param['n_classes'],
                    depth = 4,               # number of multi-scale encoding blocks
                    sm_dim = 192,            # high res dimension
                    sm_patch_size = 16,      # high res patch size (should be smaller than lg_patch_size)
                    sm_enc_depth = 2,        # high res depth
                    sm_enc_heads = 8,        # high res heads
                    sm_enc_mlp_dim = 2048,   # high res feedforward dimension
                    lg_dim = 384,            # low res dimension
                    lg_patch_size = 64,      # low res patch size
                    lg_enc_depth = 3,        # low res depth
                    lg_enc_heads = 8,        # low res heads
                    lg_enc_mlp_dim = 2048,   # low res feedforward dimensions
                    cross_attn_depth = 2,    # cross attention rounds
                    cross_attn_heads = 8,    # cross attention heads
                    dropout = 0.1,
                    emb_dropout = 0.1
                )
                
            elif model_param['model'] == 'dev_pit':
                from vit_pytorch.pit import PiT
                model = PiT(
                    image_size= model_param['image_size'],
                    patch_size= model_param['image_size']//8,
                    dim = 256,
                    num_classes  = model_param['n_classes'],
                    depth = (3, 3, 3),     # list of depths, indicating the number of rounds of each stage before a downsample
                    heads = 16,
                    mlp_dim = 2048,
                    dropout = 0.1,
                    emb_dropout = 0.1
                )
            
            elif model_param['model'] == 'dev_levit':
                from vit_pytorch.levit import LeViT
                model = LeViT(
                    image_size= model_param['image_size'],
                    num_classes  = model_param['n_classes'],
                    stages = 3,             # number of stages
                    dim = (256, 384, 512),  # dimensions at each stage
                    depth = 4,              # transformer of depth 4 at each stage
                    heads = (4, 6, 8),      # heads at each stage
                    mlp_mult = 2,
                    dropout = 0.1
                )
            
            elif model_param['model'] == 'dev_cvt':
                from vit_pytorch.cvt import CvT
                model = CvT(
                    num_classes  = model_param['n_classes'],
                    s1_emb_dim = 64,        # stage 1 - dimension
                    s1_emb_kernel = 7,      # stage 1 - conv kernel
                    s1_emb_stride = 4,      # stage 1 - conv stride
                    s1_proj_kernel = 3,     # stage 1 - attention ds-conv kernel size
                    s1_kv_proj_stride = 2,  # stage 1 - attention key / value projection stride
                    s1_heads = 1,           # stage 1 - heads
                    s1_depth = 1,           # stage 1 - depth
                    s1_mlp_mult = 4,        # stage 1 - feedforward expansion factor
                    s2_emb_dim = 192,       # stage 2 - (same as above)
                    s2_emb_kernel = 3,
                    s2_emb_stride = 2,
                    s2_proj_kernel = 3,
                    s2_kv_proj_stride = 2,
                    s2_heads = 3,
                    s2_depth = 2,
                    s2_mlp_mult = 4,
                    s3_emb_dim = 384,       # stage 3 - (same as above)
                    s3_emb_kernel = 3,
                    s3_emb_stride = 2,
                    s3_proj_kernel = 3,
                    s3_kv_proj_stride = 2,
                    s3_heads = 4,
                    s3_depth = 10,
                    s3_mlp_mult = 4,
                    dropout = 0.
                )
                model_requires_224 = True 
                
            elif model_param['model'] == 'dev_twinsvt':
                from vit_pytorch.twins_svt import TwinsSVT
                model = TwinsSVT(
                    num_classes  = model_param['n_classes'],       # number of output classes
                    s1_emb_dim = 64,          # stage 1 - patch embedding projected dimension
                    s1_patch_size = 4,        # stage 1 - patch size for patch embedding
                    s1_local_patch_size = 7,  # stage 1 - patch size for local attention
                    s1_global_k = 7,          # stage 1 - global attention key / value reduction factor, defaults to 7 as specified in paper
                    s1_depth = 1,             # stage 1 - number of transformer blocks (local attn -> ff -> global attn -> ff)
                    s2_emb_dim = 128,         # stage 2 (same as above)
                    s2_patch_size = 2,
                    s2_local_patch_size = 7,
                    s2_global_k = 7,
                    s2_depth = 1,
                    s3_emb_dim = 256,         # stage 3 (same as above)
                    s3_patch_size = 2,
                    s3_local_patch_size = 7,
                    s3_global_k = 7,
                    s3_depth = 5,
                    s4_emb_dim = 512,         # stage 4 (same as above)
                    s4_patch_size = 2,
                    s4_local_patch_size = 7,
                    s4_global_k = 7,
                    s4_depth = 4,
                    peg_kernel_size = 3,      # positional encoding generator kernel size
                    dropout = 0.              # dropout
                )    
                model_requires_224 = True 
         
            elif model_param['model'] == 'dev_regionvit':
                from vit_pytorch.regionvit import RegionViT
                model = RegionViT(
                    dim = (64, 128, 256, 512),      # tuple of size 4, indicating dimension at each stage
                    depth = (2, 2, 8, 2),           # depth of the region to local transformer at each stage
                    window_size = 7,                # window size, which should be either 7 or 14
                    num_classes  = model_param['n_classes'],             # number of output classes
                    tokenize_local_3_conv = False,  # whether to use a 3 layer convolution to encode the local tokens from the image. the paper uses this for the smaller models, but uses only 1 conv (set to False) for the larger models
                    use_peg = False,                # whether to use positional generating module. they used this for object detection for a boost in performance
                )
                model_requires_224 = True 
                           
            elif model_param['model'] == 'dev_crossformer':
                from vit_pytorch.crossformer import CrossFormer
        
                model = CrossFormer(
                    num_classes  = model_param['n_classes'],                # number of output classes
                    dim = (64, 128, 256, 512),         # dimension at each stage
                    depth = (2, 2, 8, 2),              # depth of transformer at each stage
                    global_window_size = (8, 4, 2, 1), # global window sizes at each stage
                    local_window_size = 7,             # local window size (can be customized for each stage, but in paper, held constant at 7 for all stages)
                )
                model_requires_224 = True 
                           
            elif model_param['model'] == 'dev_scalablevit':
                from vit_pytorch.scalable_vit import ScalableViT
        
                model = ScalableViT(
                    num_classes  = model_param['n_classes'],
                    dim = 64,                               # starting model dimension. at every stage, dimension is doubled
                    heads = (2, 4, 8, 16),                  # number of attention heads at each stage
                    depth = (2, 2, 20, 2),                  # number of transformer blocks at each stage
                    ssa_dim_key = (40, 40, 40, 32),         # the dimension of the attention keys (and queries) for SSA. in the paper, they represented this as a scale factor on the base dimension per key (ssa_dim_key / dim_key)
                    reduction_factor = (8, 4, 2, 1),        # downsampling of the key / values in SSA. in the paper, this was represented as (reduction_factor ** -2)
                    window_size = (64, 32, None, None),     # window size of the IWSA at each stage. None means no windowing needed
                    dropout = 0.1,                          # attention and feedforward dropout
                )
                model_requires_224 = True 
                           
            elif model_param['model'] == 'dev_sepvit':
                from vit_pytorch.sep_vit import SepViT
        
                model = SepViT(
                    num_classes  = model_param['n_classes'],
                    dim = 32,               # dimensions of first stage, which doubles every stage (32, 64, 128, 256) for SepViT-Lite
                    dim_head = 32,          # attention head dimension
                    heads = (1, 2, 4, 8),   # number of heads per stage
                    depth = (1, 2, 6, 2),   # number of transformer blocks per stage
                    window_size = 7,        # window size of DSS Attention block
                    dropout = 0.1           # dropout
                )
                model_requires_224 = True 
                
            elif model_param['model'] == 'dev_maxvit':
                from vit_pytorch.max_vit import MaxViT
        
                model = MaxViT(
                    num_classes  = model_param['n_classes'],
                    dim_conv_stem = 64,               # dimension of the convolutional stem, would default to dimension of first layer if not specified
                    dim = 96,                         # dimension of first layer, doubles every layer
                    dim_head = 32,                    # dimension of attention heads, kept at 32 in paper
                    depth = (2, 2, 5, 2),             # number of MaxViT blocks per stage, which consists of MBConv, block-like attention, grid-like attention
                    window_size = 7,                  # window size for block and grids
                    mbconv_expansion_rate = 4,        # expansion rate of MBConv
                    mbconv_shrinkage_rate = 0.25,     # shrinkage rate of squeeze-excitation in MBConv
                    dropout = 0.1                     # dropout
                )
                model_requires_224 = True 
                        
            elif model_param['model'] == 'dev_nest':
                from vit_pytorch.nest import NesT
        
                model = NesT(
                    image_size= model_param['image_size'],
                    patch_size = 4,
                    dim = 96,
                    heads = 3,
                    num_hierarchies = 3,        # number of hierarchies
                    block_repeats = (2, 2, 8),  # the number of transformer blocks at each hierarchy, starting from the bottom
                    num_classes  = model_param['n_classes']
                )
                
            elif model_param['model'] == 'dev_nest':
                from vit_pytorch.mobile_vit import MobileViT
        
                model = MobileViT(
                    image_size = (model_param['image_size'], model_param['image_size']),
                    dims = [96, 120, 144],
                    channels = [16, 32, 48, 48, 64, 64, 80, 80, 96, 96, 384],
                    num_classes  = model_param['n_classes']
                ) 
                
            elif model_param['model'] == 'dev_xcit':
                from vit_pytorch.xcit import XCiT
        
                model = XCiT(
                    image_size= model_param['image_size'],
                    patch_size = 32,
                    num_classes  = model_param['n_classes'],
                    dim = 1024,
                    depth = 12,                     # depth of xcit transformer
                    cls_depth = 2,                  # depth of cross attention of CLS tokens to patch, attention pool at end
                    heads = 16,
                    mlp_dim = 2048,
                    dropout = 0.1,
                    emb_dropout = 0.1,
                    layer_dropout = 0.05,           # randomly dropout 5% of the layers
                    local_patch_kernel_size = 3     # kernel size of the local patch interaction module (depthwise convs)
                )
                
            elif model_param['model'] == 'dev_simmim':
                from vit_pytorch import ViT
                from vit_pytorch.simmim import SimMIM
        
                v = ViT(
                    image_size= model_param['image_size'],
                    patch_size = 32,
                    num_classes  = model_param['n_classes'],
                    dim = 1024,
                    depth = 6,
                    heads = 8,
                    mlp_dim = 2048
                )
                
                model = SimMIM(
                    encoder = v,
                    masking_ratio = 0.5  # they found 50% to yield the best results
                )
        
            elif model_param['model'] == 'dev_mae':
                from vit_pytorch import ViT, MAE
                
                v = ViT(
                    image_size= model_param['image_size'],
                    patch_size = 32,
                    num_classes  = model_param['n_classes'],
                    dim = 1024,
                    depth = 6,
                    heads = 8,
                    mlp_dim = 2048
                )
                
                model = MAE(
                    encoder = v,
                    masking_ratio = 0.75,   # the paper recommended 75% masked patches
                    decoder_dim = 512,      # paper showed good results with just 512
                    decoder_depth = 6       # anywhere from 1 to 8
                )
        
            elif model_param['model'] == 'dev_smallvit':
                from vit_pytorch.vit_for_small_dataset import ViT
                
                model = ViT(
                    image_size= model_param['image_size'],
                    patch_size = 16,
                    num_classes  = model_param['n_classes'],
                    dim = 1024,
                    depth = 6,
                    heads = 16,
                    mlp_dim = 2048,
                    dropout = 0.1,
                    emb_dropout = 0.1
                )
        
        
            elif model_param['model'] == 'dev_parallelvit':
                from vit_pytorch.parallel_vit import ViT
                
                model = ViT(
                    image_size= model_param['image_size'],
                    patch_size = 16,
                    num_classes  = model_param['n_classes'],
                    dim = 1024,
                    depth = 6,
                    heads = 8,
                    mlp_dim = 2048,
                    num_parallel_branches = 2,  # in paper, they claimed 2 was optimal
                    dropout = 0.1,
                    emb_dropout = 0.1
                )
                                       
            else:
                raise Exception("Model doesn't exist.")
    
            # Parallelize train across multiple GPUs
            # model = torch.nn.DataParallel(model)
            
            # Set the model to run on the device
            model = model.to(device)
            
            # checkpoint = torch.load(opt.model_file)
            model.load_state_dict(model_data['model_state'], strict=True)
            model.eval()
       

     
            # Create datasets
            eval_dataset = SingleCellImageDataset(
                image_df, 
                image_size=model_param['image_size'],
                image_mean=model_param['image_mean'], 
                image_std=model_param['image_std'],
                model_requires_224=model_requires_224) 
            
            # Create dataloaders
            eval_dataloader = DataLoader(eval_dataset, batch_size=opt.batch_size, shuffle=False)
            
            result_json_data["predicted"] = []
            result_json_data["probability"] = []
            # result_json_data["probability_distribution"] = []
            
            with torch.no_grad():    
                for images in eval_dataloader:
                    images = images.to(device)
                    outputs = model(images)
                    
                    # _, predicted = torch.max(outputs.data, 1)
                    # result_json_data["predicted"].extend(predicted.to('cpu').tolist())
            
                    result = ((outputs.data-outputs.data.min(1, keepdim=True)[0])/(outputs.data-outputs.data.min(1, keepdim=True)[0]).sum(1, keepdim=True))
                    _, pred = result.max(axis=1)
                    result_json_data["predicted"].extend(pred.to('cpu').tolist())
                    # result_json_data["probability"].extend(prob.to('cpu').tolist())
                    # result_json_data["probability_distribution"].extend(result.to('cpu').tolist())
                    result_json_data["probability"].extend(result.to('cpu').tolist())
                    
            result_json_data["success"] = True
                    
            break
        except Exception as e:
            err_msg = repr(e)
            print(f'Exception Occurred: {err_msg} Retry {try_count+1}/{RETRY_TIMES}')
            time.sleep(RETRY_SLEEP)

    with open(opt.result_file, "w") as fp:
        json.dump(result_json_data , fp)
        
        
def estimate_w(opt):
    result_json_data = {"success": False}
    
    # Prepare image data
    image_filename_list = glob.glob(os.path.join(opt.image_path, '*'))
 
    image_size = None
    sampled_image_arraylist = []
    
    for image_filename in image_filename_list:
        img = Image.open(image_filename)
        img = img.convert('RGB')
        img = np.array(img).astype(np.float32)
    
        assert img.shape[0] == img.shape[1]
        assert image_size == None or image_size == img.shape[0]
        image_size = img.shape[0]
    
        sampled_image_arraylist.append(img.reshape((1, *img.shape)))

    sampled_image_arraylist_concat = np.concatenate(sampled_image_arraylist)
    
    W_est = htk.preprocessing.color_deconvolution.rgb_separate_stains_macenko_pca(sampled_image_arraylist_concat+EPSILON, I_0)
    
    result_json_data["W"] = W_est.reshape(9).tolist()
    result_json_data["success"] = True
            
    with open(opt.result_file, "w") as fp:
        json.dump(result_json_data , fp)
        


        
def param(opt):
    model = torch.load(opt.model_file)
    param_json_data = model['parameters']
    
    with open(opt.result_file, "w") as fp:
        json.dump(param_json_data , fp)

           
if __name__ == '__main__':
    if opt.action == 'eval':
        eval(opt)
    elif opt.action == 'estimate_w':
        estimate_w(opt)
    elif opt.action == 'normalize':
        normalize(opt)
    elif opt.action == 'param':
        param(opt)
