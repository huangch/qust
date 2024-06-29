import torch
from torch.utils.data import DataLoader
from torch.utils.data import Dataset
import torchvision.transforms as T
from PIL import Image
import numpy as np
import pandas as pd
import glob
import argparse
import os
from tensorboard import program
import webbrowser
from torch.utils.tensorboard import SummaryWriter
from tqdm import tqdm
import random
from sklearn.metrics import confusion_matrix
import seaborn as sns
import matplotlib.pyplot as plt
from torch.optim.lr_scheduler import ReduceLROnPlateau

parser = argparse.ArgumentParser()
parser.add_argument('model_name', type=str, help='model name')
parser.add_argument('-i','--image_folder_list', nargs='+', help='image folder', required=True)
parser.add_argument('-d','--data_table_list', nargs='+', help='cell table', required=True)
parser.add_argument('-o','--output_folder', help='output folder', required=True)
parser.add_argument("-p", "--pixel_size", type=float, help="size of pixels in micron", required=True)
parser.add_argument("-m", "--model", type=str, help="deep learning models. Options: resnet18, resnet34, resnet50, resnet101, resnet152, wide_resnet50_2, wide_resnet101_2, resnext50_32x4d, resnext101_32x8d, resnext101_64x4d, densenet121, densenet161, densenet169, densenet201, vit_b_16, vit_b_32, vit_l_16, vit_l_32, vit_h_14, swin_t, swin_s, swin_b, swin_v2_t, swin_v2_s, swin_v2_b, vgg11, vgg11_bn, vgg13, vgg13_bn, vgg16, vgg16_bn, vgg19_bn, maxvit_t, dev_vit, dev_simplevit, dev_deepvit, dev_cait, dev_t2tvit, dev_cct, dev_cct_14, dev_crossvit, dev_pit, dev_levit, dev_cvt, dev_twinsvt, dev_regionvit, dev_crossformer, dev_scalablevit, dev_sepvit, dev_maxvit, dev_nest, dev_nest, dev_xcit, dev_simmim, dev_mae, dev_smallvit, dev_parallelvit.", required=True)
parser.add_argument("-n", "--normalized", action='store_true', help="Indicate that the dataset is normalized.")
parser.add_argument('-pt','--pretrained', type=str, default=None, help='Load pretrained model')
parser.add_argument("-bs", "--batch_size", type=int, default=128, help="size of the batches")
parser.add_argument("-ne", "--n_epochs", type=int, default=100, help="number of epochs of training")
parser.add_argument("-nc", "--num_per_class", type=int, default=0, help="number per class for training")
parser.add_argument("-lr", "--learning_rate", type=float, default=0.0001, help="learning rate")
parser.add_argument("-tb", "--tensorboard", action='store_true', help="tensorboard")
parser.add_argument("-tp", "--tensorboard_port", type=str, default="6006", help="tensorboard port")

opt = parser.parse_args()
print(opt)

random.seed(1234)

# Set device
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

if opt.tensorboard:
    tb = program.TensorBoard()
    tb.configure(argv=[None, '--logdir', os.path.join(opt.output_folder, "runs"), '--port', opt.tensorboard_port])
    url = tb.launch()
    print(f"Tensorboard listening on {url}")
    webbrowser.open(url)
    tensorboard_writer = SummaryWriter(os.path.join(opt.output_folder, "runs"))


# Load the ImageNet Object Localization Challenge dataset
class SingleCellImageDataset(Dataset):
    def __init__(self, image_data, 
                 image_mean, image_std,
                 model_requires_224 = False,
                 # , k=5, m_list=[0,1,2,3,4]
                 ):
        'Initialization'
        
        self.image_data = image_data
        self.image_mean = image_mean 
        self.image_std = image_std
                
         
        transformObjectList = [
            T.ToTensor(),
            T.Normalize(image_mean, image_std),     
            T.RandomRotation(degrees=(0, 90)),
            T.RandomHorizontalFlip(),
            T.RandomVerticalFlip(),
        ] if not model_requires_224 else [
            T.ToTensor(),
            T.Normalize(image_mean, image_std),     
            T.RandomRotation(degrees=(0, 90)),
            T.RandomHorizontalFlip(),
            T.RandomVerticalFlip(),
            T.Resize(224),
            ] 

        self.transform = T.Compose(transformObjectList)


    def __len__(self):
        'Denotes the total number of samples'
        return len(self.image_data)


    def __getitem__(self, index):
        'Generates one sample of data'
        # Select sample
        
        label = self.image_data.iloc[index]['label']
        imageFile = self.image_data.iloc[index]['image_filepath']
        
        img = Image.open(imageFile)
        img = img.convert('RGB')
        img = np.array(img).astype(np.float32)
        img = np.array(img).astype(np.float32)
        img /= 255.0
        img = self.transform(img)
        
        return (img, int(label))
    
    # def __partition(self, lst, n):
    #     division = len(lst) / float(n)
    #     return [ lst[int(round(division * i)): int(round(division * (i + 1)))] for i in range(n) ]


def train(opt):
    # Prepare image data
        
    all_image_filename_list = []
    image_uuid_list = []
    
    image_basename_list = []
    
    for image_folder in opt.image_folder_list:
        image_filename_list = glob.glob(os.path.join(image_folder, '*'))
        all_image_filename_list.extend(image_filename_list)

        for image_filename in image_filename_list:
            basename = os.path.basename(os.path.dirname(image_filename))
            image_basename_list.append(basename)
            _, filename = os.path.split(image_filename)
            image_uuid, _ = os.path.splitext(filename)
            image_uuid = image_uuid.split('.')[0]
            image_uuid_list.append(basename+'/'+image_uuid)
    
    data_df = pd.DataFrame({'image_filepath': all_image_filename_list, 'uuid':image_uuid_list})
    data_df.set_index('uuid', inplace=True)

    table_df_list = []

    for data_table in opt.data_table_list:
        basename = os.path.basename(os.path.splitext(data_table)[0])
        if not basename in image_basename_list:
            raise Exception('table file name {} has no corresponding image folder'.format(basename))
        
        table_df = pd.read_table(data_table)
        table_df['uuid'] = table_df.apply(lambda row: basename+'/'+row['Object ID'], axis=1)
        table_df.set_index('uuid', inplace=True)
        table_df_list.append(table_df)
        
    table_df  = pd.concat(table_df_list)
    table_df.dropna(subset=['Classification'], inplace=True)
    
    cls_list = table_df['Classification'].unique().tolist()
    
    for lbl, (cls) in enumerate(cls_list):
        cls_uuid_series = table_df[table_df.Classification == cls].index
        data_df.loc[data_df.index.isin(cls_uuid_series), 'Classification'] = cls
        data_df.loc[data_df.index.isin(cls_uuid_series), 'label'] = lbl
         
    #
    # Balance classes when needed
    #
        
    balanced_df_list = []

    if opt.num_per_class != 0:
        for lbl, (cls) in enumerate(cls_list):
            print(lbl, cls)
            balanced_df_list.append(data_df[data_df['label'] == lbl].sample(n=opt.num_per_class, replace=True)) 
            
        balanced_df  = pd.concat(balanced_df_list)
    
    else:
        balanced_df = data_df
        
    
    print("Num per class:") 
    for lbl, (cls) in enumerate(cls_list):
        cls_size = len(balanced_df[balanced_df['label'] == int(lbl)])
        print(f"{cls}: {cls_size}") 
        
    #
    # Normalization
    # 
    sampled_image_filename_list = balanced_df['image_filepath'].sample(1000).tolist()
    sampled_image_arraylist = []
    #
    image_fileext = None
    image_size = None
    
    for image_filename in sampled_image_filename_list:
        img = Image.open(image_filename)
        img = img.convert('RGB')
        img = np.array(img).astype(np.float32)
    
        assert img.shape[0] == img.shape[1]
        assert image_size == None or image_size == img.shape[0]
        image_size = img.shape[0]
    
        sampled_image_arraylist.append(img.reshape((1, *img.shape)))
    
        _, filename = os.path.split(image_filename)
        _, fileext = os.path.splitext(image_filename)
    
        assert image_fileext == None or image_fileext == fileext
        image_fileext = fileext
    
    sampled_image_arraylist_concat = np.concatenate(sampled_image_arraylist)
    sampled_image_arraylist_concat = np.array(sampled_image_arraylist_concat).astype(np.float32)
    sampled_image_arraylist_concat /= 255.0
        
    image_mean = sampled_image_arraylist_concat.mean(axis=(0,1,2))
    image_std = (sampled_image_arraylist_concat-image_mean).std(axis=(0,1,2))
    

    model_requires_224 = False
    
    if opt.model == 'resnet18':
        from torchvision.models import resnet18
        model = resnet18(num_classes=len(cls_list))
        
    elif opt.model == 'resnet34':
        from torchvision.models import resnet34
        model = resnet34(num_classes=len(cls_list))
        
    elif opt.model == 'resnet50':
        from torchvision.models import resnet50
        model = resnet50(num_classes=len(cls_list))

    elif opt.model == 'resnet101':
        from torchvision.models import resnet101
        model = resnet101(num_classes=len(cls_list))
   
    elif opt.model == 'resnet152':
        from torchvision.models import resnet152
        model = resnet152(num_classes=len(cls_list))
   
    elif opt.model == 'wide_resnet50_2':
        from torchvision.models import wide_resnet50_2
        model = wide_resnet50_2(num_classes=len(cls_list))
   
    elif opt.model == 'wide_resnet101_2':
        from torchvision.models import wide_resnet101_2
        model = wide_resnet101_2(num_classes=len(cls_list))
                
    elif opt.model == 'resnext50_32x4d':
        from torchvision.models import resnext50_32x4d
        model = resnext50_32x4d(num_classes=len(cls_list))
        
    elif opt.model == 'resnext101_32x8d':
        from torchvision.models import resnext101_32x8d
        model = resnext101_32x8d(num_classes=len(cls_list))
        
    elif opt.model == 'resnext101_64x4d':
        from torchvision.models import resnext101_64x4d
        model = resnext101_64x4d(num_classes=len(cls_list))

    elif opt.model == 'densenet121':
        from torchvision.models import densenet121
        model = densenet121(num_classes=len(cls_list))

    elif opt.model == 'densenet161':
        from torchvision.models import densenet161
        model = densenet161(num_classes=len(cls_list))

    elif opt.model == 'densenet169':
        from torchvision.models import densenet169
        model = densenet169(num_classes=len(cls_list))

    elif opt.model == 'densenet201':
        from torchvision.models import densenet201
        model = densenet201(num_classes=len(cls_list))
        
    elif opt.model == 'vit_b_16':
        from torchvision.models import vit_b_16
        model = vit_b_16(image_size=image_size, num_classes=len(cls_list))
        model_requires_224 = True     

    elif opt.model == 'vit_b_32':
        from torchvision.models import vit_b_32
        model = vit_b_32(image_size=image_size, num_classes=len(cls_list)) 
        model_requires_224 = True
        
    elif opt.model == 'vit_l_16':
        from torchvision.models import vit_l_16
        model = vit_l_16(image_size=image_size, num_classes=len(cls_list)) 
        model_requires_224 = True
        
    elif opt.model == 'vit_l_32':
        from torchvision.models import vit_l_32
        model = vit_l_32(image_size=image_size, num_classes=len(cls_list)) 
        model_requires_224 = True
        
    elif opt.model == 'vit_h_14':
        from torchvision.models import vit_h_14
        model = vit_h_14(image_size=image_size, num_classes=len(cls_list)) 
        model_requires_224 = True
                
    elif opt.model == 'swin_t':
        from torchvision.models import swin_t
        model = swin_t(image_size=image_size, num_classes=len(cls_list))
        model_requires_224 = True     
        
    elif opt.model == 'swin_s':
        from torchvision.models import swin_s
        model = swin_s(image_size=image_size, num_classes=len(cls_list))
        model_requires_224 = True     

    elif opt.model == 'swin_b':
        from torchvision.models import swin_b
        model = swin_b(image_size=image_size, num_classes=len(cls_list)) 
        model_requires_224 = True     
        
    elif opt.model == 'swin_v2_t':
        from torchvision.models import swin_v2_t
        model = swin_v2_t(image_size=image_size, num_classes=len(cls_list)) 
        model_requires_224 = True     
        
    elif opt.model == 'swin_v2_s':
        from torchvision.models import swin_v2_s
        model = swin_v2_s(image_size=image_size, num_classes=len(cls_list)) 
        model_requires_224 = True     
        
    elif opt.model == 'swin_v2_b':
        from torchvision.models import swin_v2_b
        model = swin_v2_b(image_size=image_size, num_classes=len(cls_list)) 
        model_requires_224 = True     

    elif opt.model == 'vgg11':
        from torchvision.models import vgg11
        model = vgg11(num_classes=len(cls_list)) 
        
    elif opt.model == 'vgg11_bn':
        from torchvision.models import vgg11_bn
        model = vgg11_bn(num_classes=len(cls_list)) 
        
    elif opt.model == 'vgg13':
        from torchvision.models import vgg13
        model = vgg13(num_classes=len(cls_list)) 
        
    elif opt.model == 'vgg13_bn':
        from torchvision.models import vgg13_bn
        model = vgg13_bn(num_classes=len(cls_list)) 
        
    elif opt.model == 'vgg16':
        from torchvision.models import vgg16
        model = vgg16(num_classes=len(cls_list)) 
        
    elif opt.model == 'vgg16_bn':
        from torchvision.models import vgg16_bn
        model = vgg16_bn(num_classes=len(cls_list))    
        
    elif opt.model == 'vgg19_bn':
        from torchvision.models import vgg19_bn
        model = vgg19_bn(num_classes=len(cls_list))                
        
    elif opt.model == 'maxvit_t':
        from torchvision.models import maxvit_t
        model = maxvit_t(num_classes=len(cls_list))  
                                    
    elif opt.model == 'dev_vit':
        from vit_pytorch import ViT
        model = ViT(
            image_size = image_size,
            patch_size = image_size//8,
            num_classes=len(cls_list),
            dim = 1024,
            depth = 6,
            heads = 16,
            mlp_dim = 2048,
            dropout = 0.1,
            emb_dropout = 0.1
        )
        
    elif opt.model == 'dev_simplevit':
        from vit_pytorch import SimpleViT
        model = SimpleViT(
            image_size = image_size,
            patch_size = image_size//8,
            num_classes = len(cls_list),
            dim = 1024,
            depth = 6,
            heads = 16,
            mlp_dim = 2048
        )
        
    elif opt.model == 'dev_deepvit':
        from vit_pytorch.deepvit import DeepViT
        model = DeepViT(
            image_size = image_size,
            patch_size = image_size//8,
            num_classes = len(cls_list),
            dim = 1024,
            depth = 6,
            heads = 16,
            mlp_dim = 2048,
            dropout = 0.1,
            emb_dropout = 0.1
        )
        
    elif opt.model == 'dev_cait':
        from vit_pytorch.cait import CaiT
        model = CaiT(
            image_size = image_size,
            patch_size = image_size//8,
            num_classes = len(cls_list),
            dim = 1024,
            depth = 12,             # depth of transformer for patch to patch attention only
            cls_depth = 2,          # depth of cross attention of CLS tokens to patch
            heads = 16,
            mlp_dim = 2048,
            dropout = 0.1,
            emb_dropout = 0.1,
            layer_dropout = 0.05    # randomly dropout 5% of the layers
        )

    elif opt.model == 'dev_t2tvit':
        from vit_pytorch.t2t import T2TViT
        model = T2TViT(
            dim = 512,
            image_size = image_size,
            depth = 5,
            heads = 8,
            mlp_dim = 512,
            num_classes = len(cls_list),
            t2t_layers = ((7, 4), (3, 2), (3, 2)) # tuples of the kernel size and stride of each consecutive layers of the initial token to token module
        )
        
    elif opt.model == 'dev_cct':
        from vit_pytorch.cct import CCT
        model = CCT(
            img_size = (image_size, image_size),
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
            num_classes = len(cls_list),
            positional_embedding = 'learnable', # ['sine', 'learnable', 'none']
        )
        
    elif opt.model == 'dev_cct_14':
        from vit_pytorch.cct import cct_14
        model = cct_14(
            img_size = image_size,
            n_conv_layers = 1,
            kernel_size = 7,
            stride = 2,
            padding = 3,
            pooling_kernel_size = 3,
            pooling_stride = 2,
            pooling_padding = 1,
            num_classes = len(cls_list),
            positional_embedding = 'learnable', # ['sine', 'learnable', 'none']
        )
        
    elif opt.model == 'dev_crossvit':
        from vit_pytorch.cross_vit import CrossViT
        model = CrossViT(
            image_size = image_size,
            num_classes = len(cls_list),
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
        
    elif opt.model == 'dev_pit':
        from vit_pytorch.pit import PiT
        model = PiT(
            image_size = image_size,
            patch_size = image_size//8,
            dim = 256,
            num_classes = len(cls_list),
            depth = (3, 3, 3),     # list of depths, indicating the number of rounds of each stage before a downsample
            heads = 16,
            mlp_dim = 2048,
            dropout = 0.1,
            emb_dropout = 0.1
        )
    
    elif opt.model == 'dev_levit':
        from vit_pytorch.levit import LeViT
        model = LeViT(
            image_size = image_size,
            num_classes = len(cls_list),
            stages = 3,             # number of stages
            dim = (256, 384, 512),  # dimensions at each stage
            depth = 4,              # transformer of depth 4 at each stage
            heads = (4, 6, 8),      # heads at each stage
            mlp_mult = 2,
            dropout = 0.1
        )
    
    elif opt.model == 'dev_cvt':
        from vit_pytorch.cvt import CvT
        model = CvT(
            num_classes = len(cls_list),
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
        
    elif opt.model == 'dev_twinsvt':
        from vit_pytorch.twins_svt import TwinsSVT
        model = TwinsSVT(
            num_classes = len(cls_list),       # number of output classes
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
 
    elif opt.model == 'dev_regionvit':
        from vit_pytorch.regionvit import RegionViT
        model = RegionViT(
            dim = (64, 128, 256, 512),      # tuple of size 4, indicating dimension at each stage
            depth = (2, 2, 8, 2),           # depth of the region to local transformer at each stage
            window_size = 7,                # window size, which should be either 7 or 14
            num_classes = len(cls_list),             # number of output classes
            tokenize_local_3_conv = False,  # whether to use a 3 layer convolution to encode the local tokens from the image. the paper uses this for the smaller models, but uses only 1 conv (set to False) for the larger models
            use_peg = False,                # whether to use positional generating module. they used this for object detection for a boost in performance
        )
        model_requires_224 = True 
                   
    elif opt.model == 'dev_crossformer':
        from vit_pytorch.crossformer import CrossFormer

        model = CrossFormer(
            num_classes = len(cls_list),                # number of output classes
            dim = (64, 128, 256, 512),         # dimension at each stage
            depth = (2, 2, 8, 2),              # depth of transformer at each stage
            global_window_size = (8, 4, 2, 1), # global window sizes at each stage
            local_window_size = 7,             # local window size (can be customized for each stage, but in paper, held constant at 7 for all stages)
        )
        model_requires_224 = True 
                   
    elif opt.model == 'dev_scalablevit':
        from vit_pytorch.scalable_vit import ScalableViT

        model = ScalableViT(
            num_classes = len(cls_list),
            dim = 64,                               # starting model dimension. at every stage, dimension is doubled
            heads = (2, 4, 8, 16),                  # number of attention heads at each stage
            depth = (2, 2, 20, 2),                  # number of transformer blocks at each stage
            ssa_dim_key = (40, 40, 40, 32),         # the dimension of the attention keys (and queries) for SSA. in the paper, they represented this as a scale factor on the base dimension per key (ssa_dim_key / dim_key)
            reduction_factor = (8, 4, 2, 1),        # downsampling of the key / values in SSA. in the paper, this was represented as (reduction_factor ** -2)
            window_size = (64, 32, None, None),     # window size of the IWSA at each stage. None means no windowing needed
            dropout = 0.1,                          # attention and feedforward dropout
        )
        model_requires_224 = True 
                   
    elif opt.model == 'dev_sepvit':
        from vit_pytorch.sep_vit import SepViT

        model = SepViT(
            num_classes = len(cls_list),
            dim = 32,               # dimensions of first stage, which doubles every stage (32, 64, 128, 256) for SepViT-Lite
            dim_head = 32,          # attention head dimension
            heads = (1, 2, 4, 8),   # number of heads per stage
            depth = (1, 2, 6, 2),   # number of transformer blocks per stage
            window_size = 7,        # window size of DSS Attention block
            dropout = 0.1           # dropout
        )
        model_requires_224 = True 
        
    elif opt.model == 'dev_maxvit':
        from vit_pytorch.max_vit import MaxViT

        model = MaxViT(
            num_classes = len(cls_list),
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
                
    elif opt.model == 'dev_nest':
        from vit_pytorch.nest import NesT

        model = NesT(
            image_size = image_size,
            patch_size = image_size//8,
            dim = 96,
            heads = 3,
            num_hierarchies = 3,        # number of hierarchies
            block_repeats = (2, 2, 8),  # the number of transformer blocks at each hierarchy, starting from the bottom
            num_classes = len(cls_list)
        )
        
    elif opt.model == 'dev_nest':
        from vit_pytorch.mobile_vit import MobileViT

        model = MobileViT(
            image_size = (image_size, image_size),
            dims = [96, 120, 144],
            channels = [16, 32, 48, 48, 64, 64, 80, 80, 96, 96, 384],
            num_classes = len(cls_list)
        ) 
        
    elif opt.model == 'dev_xcit':
        from vit_pytorch.xcit import XCiT

        model = XCiT(
            image_size = image_size,
            patch_size = image_size//8,
            num_classes = len(cls_list),
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
        
    elif opt.model == 'dev_simmim':
        from vit_pytorch import ViT
        from vit_pytorch.simmim import SimMIM

        v = ViT(
            image_size = image_size,
            patch_size = image_size//8,
            num_classes = len(cls_list),
            dim = 1024,
            depth = 6,
            heads = 8,
            mlp_dim = 2048
        )
        
        model = SimMIM(
            encoder = v,
            masking_ratio = 0.5  # they found 50% to yield the best results
        )

    elif opt.model == 'dev_mae':
        from vit_pytorch import ViT, MAE
        
        v = ViT(
            image_size = image_size,
            patch_size = image_size//8,
            num_classes = len(cls_list),
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

    elif opt.model == 'dev_smallvit':
        from vit_pytorch.vit_for_small_dataset import ViT
        
        model = ViT(
            image_size = image_size,
            patch_size = image_size//8,
            num_classes = len(cls_list),
            dim = 1024,
            depth = 6,
            heads = 16,
            mlp_dim = 2048,
            dropout = 0.1,
            emb_dropout = 0.1
        )


    elif opt.model == 'dev_parallelvit':
        from vit_pytorch.parallel_vit import ViT
        
        model = ViT(
            image_size = image_size,
            patch_size = image_size//8,
            num_classes = len(cls_list),
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
    # Possibly pytorch bug, DataParallel is not compatible with tensorboard
    # if not opt.tensorboard:
    #     model = torch.nn.DataParallel(model)
    
    # Set the model to run on the device
    model = model.to(device)

    # load pretrained
    if opt.pretrained is not None:
        checkpoint = torch.load(opt.pretrained)
        model.load_state_dict(checkpoint['model_state'], strict=True)
        
        
        
        
        
        
    # Curate image and transcript data by cell UUID
    # total_uuid_list = [value for value in balanced_df.index]
    # random.shuffle(total_uuid_list)
    balanced_df = balanced_df.sample(frac=1).reset_index(drop=True)

    # Split all data into train, validation and test uuids.
    # train_uuid_list = total_uuid_list[:int(len(total_uuid_list)*0.9)]
    # validation_uuid_list = total_uuid_list[int(len(total_uuid_list)*0.9):]

    # Create image data according to train, validation and test uuids.
    # train_df = balanced_df.reindex(train_uuid_list)
    # validation_df = balanced_df.reindex(validation_uuid_list)

    train_df = balanced_df.sample(frac = 0.9)
    validation_df = balanced_df.drop(train_df.index)
    
    # Create datasets
    train_dataset = SingleCellImageDataset(
        train_df, 
        image_mean=image_mean, 
        image_std=image_std,
        model_requires_224  = model_requires_224) 
    
    validation_dataset = SingleCellImageDataset(
        validation_df,  
        image_mean=image_mean, 
        image_std=image_std,
        model_requires_224  = model_requires_224) 
        
    # Create dataloaders
    train_dataloader = DataLoader(train_dataset, batch_size=opt.batch_size, shuffle=True, num_workers=1, pin_memory=True)
    val_dataloader = DataLoader(validation_dataset, batch_size=opt.batch_size, shuffle=True, num_workers=1, pin_memory=True)
    
    param_json_data = {
        "parameters": vars(opt),
        "image_size": image_size,
        "image_std": image_std.tolist(),
        "image_mean": image_mean.tolist(),
        "pixel_size": opt.pixel_size,
        "normalized": opt.normalized,
        "model_requires_224": model_requires_224,
        "model": opt.model,
        "n_classes": len(cls_list),
        "label_list": ";".join([str(i) for i in cls_list])
        }
        
    print('\n')
    print(param_json_data)
    print('\n')
    
    
    
    
    
    
            
    # Define the loss function and optimizer
    criterion = torch.nn.CrossEntropyLoss()
    optimizer = torch.optim.Adam(model.parameters(), lr=opt.learning_rate)
    scheduler = ReduceLROnPlateau(optimizer, mode='min', factor=0.1, patience=5, threshold=0.0001, threshold_mode='rel', cooldown=0, min_lr=0, eps=1e-08) 
                                  
    if opt.tensorboard:
        inputs, labels = next(iter(train_dataloader))
        inputs, labels = inputs.to(device), labels.to(device)
        tensorboard_writer.add_graph(model, inputs)
      
    last_cls_acc = 0  
    
    # Train the model...
    for epoch in tqdm(range(opt.n_epochs)):
        losses = []
        
        for inputs, labels in train_dataloader:
            # Move input and label tensors to the device
            inputs = inputs.to(device)
            labels = labels.to(device)
    
            # Zero out the optimizer
            optimizer.zero_grad()
    
            # Forward pass
            outputs = model(inputs)
            loss = criterion(outputs, labels)
            losses.append(loss.item())
            
            # Backward pass
            loss.backward()
            optimizer.step()
            
            if opt.tensorboard:
                tensorboard_writer.add_scalar("train_loss", loss.item(), epoch)
                tensorboard_writer.flush()
        
        avg_loss = sum(losses)/len(losses)
        scheduler.step(avg_loss)
        
        correct = 0
        total = 0
    
        y_pred = []
        y_true = []
            
        model.eval()
        with torch.no_grad():
            for images, labels in val_dataloader:
                images, labels = images.to(device), labels.to(device)
                outputs = model(images)
                
                _, predicted = torch.max(outputs.data, 1)
                
                y_pred.extend(predicted.cpu().numpy().tolist())
                y_true.extend(labels.cpu().numpy().tolist())
                            
                total += labels.size(0)
                correct += (predicted == labels).sum().item()
        
        val_cls_acc = correct/total
      
        if opt.tensorboard:
            tensorboard_writer.add_scalar("val_acc", 100*val_cls_acc, epoch)

        if val_cls_acc > last_cls_acc:
            # Build confusion matrix
            cf_matrix = confusion_matrix(y_true, y_pred)
            df_cm = pd.DataFrame(cf_matrix / np.sum(cf_matrix, axis=1)[:, None], index = [i for i in cls_list],
                                 columns = [i for i in cls_list])
            fig = plt.figure(figsize = (12,7))
            sns.heatmap(df_cm, annot=True)
            plt.savefig(os.path.join(opt.output_folder, opt.model_name+'-{:}-{:.1f}-confusion_matrix.png'.format(epoch, 100*val_cls_acc)), bbox_inches='tight')
            plt.close(fig)
            
            last_cls_acc = val_cls_acc
            last_ckpt = os.path.join(opt.output_folder, opt.model_name+"-{:}-{:.1f}.ckpt.pt".format(epoch, 100*val_cls_acc))
            torch.save({'model_state': model.state_dict(),
                        'parameters': param_json_data
                        }, last_ckpt)
            
    torch.save({'model_state': model.state_dict(),
                'parameters': param_json_data
                }, os.path.join(opt.output_folder, opt.model_name+".final.pt"))
    
    y_pred = []
    y_true = []

    model.eval()
    with torch.no_grad():
        for images, labels in val_dataloader:
            outputs = model(images.to(device))
            _, predicted = torch.max(outputs.data, 1)
            y_pred.extend(predicted.cpu().numpy().tolist())
            y_true.extend(labels.cpu().numpy().tolist())

    # Build confusion matrix
    cf_matrix = confusion_matrix(y_true, y_pred)
    df_cm = pd.DataFrame(cf_matrix / np.sum(cf_matrix, axis=1)[:, None], index = [i for i in cls_list],
                         columns = [i for i in cls_list])
    fig = plt.figure(figsize = (12,7))
    sns.heatmap(df_cm, annot=True)
    plt.savefig(os.path.join(opt.output_folder, opt.model_name+'-confusion_matrix.png'), bbox_inches='tight')
    plt.close(fig)
    input("Training Done! Press Enter to continue...")


if __name__ == '__main__':
    train(opt)
