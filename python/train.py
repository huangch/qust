
import argparse
import glob
import os
import random
import webbrowser

import numpy as np
import pandas as pd
import seaborn as sns
import torch
import torch.nn as nn
import torchvision.transforms as T
from PIL import Image
from sklearn.metrics import confusion_matrix
from tensorboard import program
from torch.optim.lr_scheduler import ReduceLROnPlateau
from torch.utils.data import DataLoader, Dataset
from torch.utils.tensorboard import SummaryWriter
from tqdm import tqdm
import matplotlib.pyplot as plt

# ---------------------------
# CONSTANTS & GLOBAL SETTINGS
# ---------------------------

NORMALIZATION_SAMPLING_SIZE = 1000

# Reproducibility
SEED = 1234
random.seed(SEED)
np.random.seed(SEED)
torch.manual_seed(SEED)
if torch.cuda.is_available():
    torch.cuda.manual_seed_all(SEED)
    torch.backends.cudnn.benchmark = True  # speed for fixed-size images

# ---------------------------
# CLI PARSER
# ---------------------------

parser = argparse.ArgumentParser()
parser.add_argument('model_name', type=str, help='model name')
parser.add_argument('-i', '--image_folder_list', nargs='+', help='image folder', required=True)
parser.add_argument('-d', '--data_table_list', nargs='+', help='cell table', required=True)
parser.add_argument('-o', '--output_folder', help='output folder', required=True)
parser.add_argument("-p", "--pixel_size", type=float, help="size of pixels in micron", required=True)
parser.add_argument(
    "-m", "--model", type=str, required=True,
    help=("deep learning models. Options: resnet18, resnet34, resnet50, resnet101, resnet152, "
          "wide_resnet50_2, wide_resnet101_2, resnext50_32x4d, resnext101_32x8d, resnext101_64x4d, "
          "densenet121, densenet161, densenet169, densenet201, vit_b_16, vit_b_32, vit_l_16, vit_l_32, "
          "vit_h_14, swin_t, swin_s, swin_b, swin_v2_t, swin_v2_s, swin_v2_b, vgg11, vgg11_bn, vgg13, "
          "vgg13_bn, vgg16, vgg16_bn, vgg19_bn, maxvit_t, dev_vit, dev_simplevit, dev_deepvit, dev_cait, "
          "dev_t2tvit, dev_cct, dev_cct_14, dev_crossvit, dev_pit, dev_levit, dev_cvt, dev_twinsvt, "
          "dev_regionvit, dev_crossformer, dev_scalablevit, dev_sepvit, dev_maxvit, dev_nest, "
          "dev_xcit, dev_simmim, dev_mae, dev_smallvit, dev_parallelvit, dev_mobilevit.")
)
parser.add_argument("-n", "--normalized", action='store_true', help="Indicate that the dataset is normalized.")
parser.add_argument('-pt', '--pretrained', type=str, default=None, help='Load pretrained model checkpoint')
parser.add_argument("-bs", "--batch_size", type=int, default=128, help="size of the batches")
parser.add_argument("-ne", "--n_epochs", type=int, default=100, help="number of epochs of training")
parser.add_argument("-nc", "--num_per_class", type=int, default=0, help="number per class for training (0 = all)")
parser.add_argument("-lr", "--learning_rate", type=float, default=0.0001, help="learning rate. Default: 0.0001")
parser.add_argument("-wd", "--weight_decay", type=float, default=0.0001, help="weight decay. Default: 0.0001")
parser.add_argument("-tb", "--tensorboard", action='store_true', help="tensorboard")
parser.add_argument("-tp", "--tensorboard_port", type=str, default="6006", help="tensorboard port")
parser.add_argument(
    "--num_workers", type=int,
    default=max(1, (os.cpu_count() or 2) // 2),
    help="Number of DataLoader worker processes (default: half of CPU cores)."
)
parser.add_argument(
    "--amp", action="store_true",
    help="Enable mixed precision training (automatic mixed precision on CUDA)."
)
parser.add_argument(
    "--pause_on_finish", action="store_true",
    help="Wait for user input at the end of training (for interactive runs)."
)

opt = parser.parse_args()
print(opt)

# Ensure output folder exists
os.makedirs(opt.output_folder, exist_ok=True)

# Set device
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

# TensorBoard setup
tensorboard_writer = None
if opt.tensorboard:
    tb = program.TensorBoard()
    tb.configure(argv=[None, '--logdir', os.path.join(opt.output_folder, "runs"),
                       '--port', opt.tensorboard_port])
    url = tb.launch()
    print(f"TensorBoard listening on {url}")
    webbrowser.open(url)
    tensorboard_writer = SummaryWriter(os.path.join(opt.output_folder, "runs"))


# ---------------------------
# DATASET
# ---------------------------

class SingleCellImageDataset(Dataset):
    def __init__(self, image_data, image_mean, image_std,
                 model_requires_224=False, train=True):
        """
        Parameters
        ----------
        image_data : pandas.DataFrame
            Must contain 'image_filepath' and 'label'.
        image_mean, image_std : iterable of 3 floats
            Normalization statistics.
        model_requires_224 : bool
            If True, resize to 224x224.
        train : bool
            If True, apply augmentations. Otherwise, deterministic transforms.
        """
        self.image_data = image_data.reset_index(drop=True)
        self.image_mean = image_mean
        self.image_std = image_std
        self.model_requires_224 = model_requires_224
        self.train = train

        transform_list = []

        # Resize (if required by model) - applied on PIL before ToTensor
        if self.model_requires_224:
            transform_list.append(T.Resize((224, 224)))

        # Data augmentation only for training
        if self.train:
            transform_list.extend([
                T.RandomRotation(degrees=(0, 90)),
                T.RandomHorizontalFlip(),
                T.RandomVerticalFlip(),
            ])

        # Convert to tensor and normalize last
        transform_list.extend([
            T.ToTensor(),  # converts to [0,1]
            T.Normalize(self.image_mean, self.image_std)
        ])

        self.transform = T.Compose(transform_list)

    def __len__(self):
        return len(self.image_data)

    def __getitem__(self, index):
        row = self.image_data.iloc[index]
        label = int(row['label'])
        image_file = row['image_filepath']

        img = Image.open(image_file).convert('RGB')
        img = self.transform(img)

        return img, label


# ---------------------------
# MODEL BUILD & UTILITIES
# ---------------------------

def build_model(model_name, num_classes, image_size, model_requires_224_flag):
    """
    Returns: model (nn.Module), model_requires_224 (bool)
    """
    model_requires_224 = False

    if model_name == 'resnet18':
        from torchvision.models import resnet18
        model = resnet18(num_classes=num_classes)

    elif model_name == 'resnet34':
        from torchvision.models import resnet34
        model = resnet34(num_classes=num_classes)

    elif model_name == 'resnet50':
        from torchvision.models import resnet50
        model = resnet50(num_classes=num_classes)

    elif model_name == 'resnet101':
        from torchvision.models import resnet101
        model = resnet101(num_classes=num_classes)

    elif model_name == 'resnet152':
        from torchvision.models import resnet152
        model = resnet152(num_classes=num_classes)

    elif model_name == 'wide_resnet50_2':
        from torchvision.models import wide_resnet50_2
        model = wide_resnet50_2(num_classes=num_classes)

    elif model_name == 'wide_resnet101_2':
        from torchvision.models import wide_resnet101_2
        model = wide_resnet101_2(num_classes=num_classes)

    elif model_name == 'resnext50_32x4d':
        from torchvision.models import resnext50_32x4d
        model = resnext50_32x4d(num_classes=num_classes)

    elif model_name == 'resnext101_32x8d':
        from torchvision.models import resnext101_32x8d
        model = resnext101_32x8d(num_classes=num_classes)

    elif model_name == 'resnext101_64x4d':
        from torchvision.models import resnext101_64x4d
        model = resnext101_64x4d(num_classes=num_classes)

    elif model_name == 'densenet121':
        from torchvision.models import densenet121
        model = densenet121(num_classes=num_classes)

    elif model_name == 'densenet161':
        from torchvision.models import densenet161
        model = densenet161(num_classes=num_classes)

    elif model_name == 'densenet169':
        from torchvision.models import densenet169
        model = densenet169(num_classes=num_classes)

    elif model_name == 'densenet201':
        from torchvision.models import densenet201
        model = densenet201(num_classes=num_classes)

    elif model_name == 'vit_b_16':
        from torchvision.models import vit_b_16
        model = vit_b_16(image_size=image_size, num_classes=num_classes)
        model_requires_224 = True

    elif model_name == 'vit_b_32':
        from torchvision.models import vit_b_32
        model = vit_b_32(image_size=image_size, num_classes=num_classes)
        model_requires_224 = True

    elif model_name == 'vit_l_16':
        from torchvision.models import vit_l_16
        model = vit_l_16(image_size=image_size, num_classes=num_classes)
        model_requires_224 = True

    elif model_name == 'vit_l_32':
        from torchvision.models import vit_l_32
        model = vit_l_32(image_size=image_size, num_classes=num_classes)
        model_requires_224 = True

    elif model_name == 'vit_h_14':
        from torchvision.models import vit_h_14
        model = vit_h_14(image_size=image_size, num_classes=num_classes)
        model_requires_224 = True

    elif model_name == 'swin_t':
        from torchvision.models import swin_t
        model = swin_t(image_size=image_size, num_classes=num_classes)
        model_requires_224 = True

    elif model_name == 'swin_s':
        from torchvision.models import swin_s
        model = swin_s(image_size=image_size, num_classes=num_classes)
        model_requires_224 = True

    elif model_name == 'swin_b':
        from torchvision.models import swin_b
        model = swin_b(image_size=image_size, num_classes=num_classes)
        model_requires_224 = True

    elif model_name == 'swin_v2_t':
        from torchvision.models import swin_v2_t
        model = swin_v2_t(image_size=image_size, num_classes=num_classes)
        model_requires_224 = True

    elif model_name == 'swin_v2_s':
        from torchvision.models import swin_v2_s
        model = swin_v2_s(image_size=image_size, num_classes=num_classes)
        model_requires_224 = True

    elif model_name == 'swin_v2_b':
        from torchvision.models import swin_v2_b
        model = swin_v2_b(image_size=image_size, num_classes=num_classes)
        model_requires_224 = True

    elif model_name == 'vgg11':
        from torchvision.models import vgg11
        model = vgg11(num_classes=num_classes)

    elif model_name == 'vgg11_bn':
        from torchvision.models import vgg11_bn
        model = vgg11_bn(num_classes=num_classes)

    elif model_name == 'vgg13':
        from torchvision.models import vgg13
        model = vgg13(num_classes=num_classes)

    elif model_name == 'vgg13_bn':
        from torchvision.models import vgg13_bn
        model = vgg13_bn(num_classes=num_classes)

    elif model_name == 'vgg16':
        from torchvision.models import vgg16
        model = vgg16(num_classes=num_classes)

    elif model_name == 'vgg16_bn':
        from torchvision.models import vgg16_bn
        model = vgg16_bn(num_classes=num_classes)

    elif model_name == 'vgg19_bn':
        from torchvision.models import vgg19_bn
        model = vgg19_bn(num_classes=num_classes)

    elif model_name == 'maxvit_t':
        from torchvision.models import maxvit_t
        model = maxvit_t(num_classes=num_classes)

    elif model_name == 'dev_vit':
        from vit_pytorch import ViT
        model = ViT(
            image_size=image_size,
            patch_size=image_size // 8,
            num_classes=num_classes,
            dim=1024,
            depth=6,
            heads=16,
            mlp_dim=2048,
            dropout=0.1,
            emb_dropout=0.1
        )

    elif model_name == 'dev_simplevit':
        from vit_pytorch import SimpleViT
        model = SimpleViT(
            image_size=image_size,
            patch_size=image_size // 8,
            num_classes=num_classes,
            dim=1024,
            depth=6,
            heads=16,
            mlp_dim=2048
        )

    elif model_name == 'dev_deepvit':
        from vit_pytorch.deepvit import DeepViT
        model = DeepViT(
            image_size=image_size,
            patch_size=image_size // 8,
            num_classes=num_classes,
            dim=1024,
            depth=6,
            heads=16,
            mlp_dim=2048,
            dropout=0.1,
            emb_dropout=0.1
        )

    elif model_name == 'dev_cait':
        from vit_pytorch.cait import CaiT
        model = CaiT(
            image_size=image_size,
            patch_size=image_size // 8,
            num_classes=num_classes,
            dim=1024,
            depth=12,
            cls_depth=2,
            heads=16,
            mlp_dim=2048,
            dropout=0.1,
            emb_dropout=0.1,
            layer_dropout=0.05
        )

    elif model_name == 'dev_t2tvit':
        from vit_pytorch.t2t import T2TViT
        model = T2TViT(
            dim=512,
            image_size=image_size,
            depth=5,
            heads=8,
            mlp_dim=512,
            num_classes=num_classes,
            t2t_layers=((7, 4), (3, 2), (3, 2))
        )

    elif model_name == 'dev_cct':
        from vit_pytorch.cct import CCT
        model = CCT(
            img_size=(image_size, image_size),
            embedding_dim=384,
            n_conv_layers=2,
            kernel_size=7,
            stride=2,
            padding=3,
            pooling_kernel_size=3,
            pooling_stride=2,
            pooling_padding=1,
            num_layers=14,
            num_heads=6,
            mlp_ratio=3.,
            num_classes=num_classes,
            positional_embedding='learnable',
        )

    elif model_name == 'dev_cct_14':
        from vit_pytorch.cct import cct_14
        model = cct_14(
            img_size=image_size,
            n_conv_layers=1,
            kernel_size=7,
            stride=2,
            padding=3,
            pooling_kernel_size=3,
            pooling_stride=2,
            pooling_padding=1,
            num_classes=num_classes,
            positional_embedding='learnable',
        )

    elif model_name == 'dev_crossvit':
        from vit_pytorch.cross_vit import CrossViT
        model = CrossViT(
            image_size=image_size,
            num_classes=num_classes,
            depth=4,
            sm_dim=192,
            sm_patch_size=16,
            sm_enc_depth=2,
            sm_enc_heads=8,
            sm_enc_mlp_dim=2048,
            lg_dim=384,
            lg_patch_size=64,
            lg_enc_depth=3,
            lg_enc_heads=8,
            lg_enc_mlp_dim=2048,
            cross_attn_depth=2,
            cross_attn_heads=8,
            dropout=0.1,
            emb_dropout=0.1
        )

    elif model_name == 'dev_pit':
        from vit_pytorch.pit import PiT
        model = PiT(
            image_size=image_size,
            patch_size=image_size // 8,
            dim=256,
            num_classes=num_classes,
            depth=(3, 3, 3),
            heads=16,
            mlp_dim=2048,
            dropout=0.1,
            emb_dropout=0.1
        )

    elif model_name == 'dev_levit':
        from vit_pytorch.levit import LeViT
        model = LeViT(
            image_size=image_size,
            num_classes=num_classes,
            stages=3,
            dim=(256, 384, 512),
            depth=4,
            heads=(4, 6, 8),
            mlp_mult=2,
            dropout=0.1
        )

    elif model_name == 'dev_cvt':
        from vit_pytorch.cvt import CvT
        model = CvT(
            num_classes=num_classes,
            s1_emb_dim=64,
            s1_emb_kernel=7,
            s1_emb_stride=4,
            s1_proj_kernel=3,
            s1_kv_proj_stride=2,
            s1_heads=1,
            s1_depth=1,
            s1_mlp_mult=4,
            s2_emb_dim=192,
            s2_emb_kernel=3,
            s2_emb_stride=2,
            s2_proj_kernel=3,
            s2_kv_proj_stride=2,
            s2_heads=3,
            s2_depth=2,
            s2_mlp_mult=4,
            s3_emb_dim=384,
            s3_emb_kernel=3,
            s3_emb_stride=2,
            s3_proj_kernel=3,
            s3_kv_proj_stride=2,
            s3_heads=4,
            s3_depth=10,
            s3_mlp_mult=4,
            dropout=0.
        )
        model_requires_224 = True

    elif model_name == 'dev_twinsvt':
        from vit_pytorch.twins_svt import TwinsSVT
        model = TwinsSVT(
            num_classes=num_classes,
            s1_emb_dim=64,
            s1_patch_size=4,
            s1_local_patch_size=7,
            s1_global_k=7,
            s1_depth=1,
            s2_emb_dim=128,
            s2_patch_size=2,
            s2_local_patch_size=7,
            s2_global_k=7,
            s2_depth=1,
            s3_emb_dim=256,
            s3_patch_size=2,
            s3_local_patch_size=7,
            s3_global_k=7,
            s3_depth=5,
            s4_emb_dim=512,
            s4_patch_size=2,
            s4_local_patch_size=7,
            s4_global_k=7,
            s4_depth=4,
            peg_kernel_size=3,
            dropout=0.
        )
        model_requires_224 = True

    elif model_name == 'dev_regionvit':
        from vit_pytorch.regionvit import RegionViT
        model = RegionViT(
            dim=(64, 128, 256, 512),
            depth=(2, 2, 8, 2),
            window_size=7,
            num_classes=num_classes,
            tokenize_local_3_conv=False,
            use_peg=False,
        )
        model_requires_224 = True

    elif model_name == 'dev_crossformer':
        from vit_pytorch.crossformer import CrossFormer
        model = CrossFormer(
            num_classes=num_classes,
            dim=(64, 128, 256, 512),
            depth=(2, 2, 8, 2),
            global_window_size=(8, 4, 2, 1),
            local_window_size=7,
        )
        model_requires_224 = True

    elif model_name == 'dev_scalablevit':
        from vit_pytorch.scalable_vit import ScalableViT
        model = ScalableViT(
            num_classes=num_classes,
            dim=64,
            heads=(2, 4, 8, 16),
            depth=(2, 2, 20, 2),
            ssa_dim_key=(40, 40, 40, 32),
            reduction_factor=(8, 4, 2, 1),
            window_size=(64, 32, None, None),
            dropout=0.1,
        )
        model_requires_224 = True

    elif model_name == 'dev_sepvit':
        from vit_pytorch.sep_vit import SepViT
        model = SepViT(
            num_classes=num_classes,
            dim=32,
            dim_head=32,
            heads=(1, 2, 4, 8),
            depth=(1, 2, 6, 2),
            window_size=7,
            dropout=0.1
        )
        model_requires_224 = True

    elif model_name == 'dev_maxvit':
        from vit_pytorch.max_vit import MaxViT
        model = MaxViT(
            num_classes=num_classes,
            dim_conv_stem=64,
            dim=96,
            dim_head=32,
            depth=(2, 2, 5, 2),
            window_size=7,
            mbconv_expansion_rate=4,
            mbconv_shrinkage_rate=0.25,
            dropout=0.1
        )
        model_requires_224 = True

    elif model_name == 'dev_nest':
        from vit_pytorch.nest import NesT
        model = NesT(
            image_size=image_size,
            patch_size=image_size // 8,
            dim=96,
            heads=3,
            num_hierarchies=3,
            block_repeats=(2, 2, 8),
            num_classes=num_classes
        )

    elif model_name == 'dev_mobilevit':
        # Previously duplicated 'dev_nest'; giving MobileViT its own name
        from vit_pytorch.mobile_vit import MobileViT
        model = MobileViT(
            image_size=(image_size, image_size),
            dims=[96, 120, 144],
            channels=[16, 32, 48, 48, 64, 64, 80, 80, 96, 96, 384],
            num_classes=num_classes
        )

    elif model_name == 'dev_xcit':
        from vit_pytorch.xcit import XCiT
        model = XCiT(
            image_size=image_size,
            patch_size=image_size // 8,
            num_classes=num_classes,
            dim=1024,
            depth=12,
            cls_depth=2,
            heads=16,
            mlp_dim=2048,
            dropout=0.1,
            emb_dropout=0.1,
            layer_dropout=0.05,
            local_patch_kernel_size=3
        )

    elif model_name == 'dev_simmim':
        from vit_pytorch import ViT
        from vit_pytorch.simmim import SimMIM
        v = ViT(
            image_size=image_size,
            patch_size=image_size // 8,
            num_classes=num_classes,
            dim=1024,
            depth=6,
            heads=8,
            mlp_dim=2048
        )
        model = SimMIM(
            encoder=v,
            masking_ratio=0.5
        )

    elif model_name == 'dev_mae':
        from vit_pytorch import ViT, MAE
        v = ViT(
            image_size=image_size,
            patch_size=image_size // 8,
            num_classes=num_classes,
            dim=1024,
            depth=6,
            heads=8,
            mlp_dim=2048
        )
        model = MAE(
            encoder=v,
            masking_ratio=0.75,
            decoder_dim=512,
            decoder_depth=6
        )

    elif model_name == 'dev_smallvit':
        from vit_pytorch.vit_for_small_dataset import ViT
        model = ViT(
            image_size=image_size,
            patch_size=image_size // 8,
            num_classes=num_classes,
            dim=1024,
            depth=6,
            heads=16,
            mlp_dim=2048,
            dropout=0.1,
            emb_dropout=0.1
        )

    elif model_name == 'dev_parallelvit':
        from vit_pytorch.parallel_vit import ViT
        model = ViT(
            image_size=image_size,
            patch_size=image_size // 8,
            num_classes=num_classes,
            dim=1024,
            depth=6,
            heads=8,
            mlp_dim=2048,
            num_parallel_branches=2,
            dropout=0.1,
            emb_dropout=0.1
        )

    else:
        raise Exception("Model '{}' doesn't exist.".format(model_name))

    # If caller already determined it requires 224, keep True
    model_requires_224 = model_requires_224 or model_requires_224_flag
    return model, model_requires_224


def strip_module_prefix_if_present(state_dict):
    """Handle checkpoints from DataParallel (keys starting with 'module.')."""
    if not state_dict:
        return state_dict
    keys = list(state_dict.keys())
    if all(k.startswith('module.') for k in keys):
        # Strip 'module.' prefix
        new_state_dict = {}
        for k, v in state_dict.items():
            new_state_dict[k[len('module.'):]] = v
        return new_state_dict
    return state_dict


# ---------------------------
# MAIN TRAINING FUNCTION
# ---------------------------

def train(opt):
    # -----------------------
    # Prepare image data paths
    # -----------------------
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
            image_uuid_list.append(basename + '/' + image_uuid)

    data_df = pd.DataFrame({'image_filepath': all_image_filename_list,
                            'uuid': image_uuid_list})
    data_df.set_index('uuid', inplace=True)

    # -----------------------
    # Load annotation tables
    # -----------------------
    table_df_list = []
    for data_table in opt.data_table_list:
        basename = os.path.basename(os.path.splitext(data_table)[0])
        if basename not in image_basename_list:
            raise Exception(
                'table file name {} has no corresponding image folder'.format(basename)
            )
        table_df = pd.read_table(data_table)
        table_df['uuid'] = table_df.apply(
            lambda row: basename + '/' + str(row['Object ID']),
            axis=1
        )
        table_df.set_index('uuid', inplace=True)
        table_df_list.append(table_df)

    table_df = pd.concat(table_df_list)

    # Remove rows without classification
    table_df.dropna(subset=['Classification'], inplace=True)

    cls_list = table_df['Classification'].unique().tolist()

    # Map class strings to integer labels
    for lbl, cls in enumerate(cls_list):
        cls_uuid_series = table_df[table_df.Classification == cls].index
        data_df.loc[data_df.index.isin(cls_uuid_series), 'Classification'] = cls
        data_df.loc[data_df.index.isin(cls_uuid_series), 'label'] = lbl

    data_df.dropna(subset=['label'], inplace=True)

    # -----------------------
    # Class balancing (optional)
    # -----------------------
    if opt.num_per_class != 0:
        balanced_df_list = []
        for lbl, cls in enumerate(cls_list):
            print(f"Sampling {opt.num_per_class} for class {lbl} ({cls})")
            balanced_df_list.append(
                data_df[data_df['label'] == lbl].sample(
                    n=opt.num_per_class, replace=True, random_state=SEED
                )
            )
        balanced_df = pd.concat(balanced_df_list)
    else:
        balanced_df = data_df

    print("Num per class in balanced dataset:")
    for lbl, cls in enumerate(cls_list):
        cls_size = len(balanced_df[balanced_df['label'] == int(lbl)])
        print(f"  {cls}: {cls_size}")

    # -----------------------
    # Normalization statistics
    # -----------------------
    if len(balanced_df['image_filepath']) > NORMALIZATION_SAMPLING_SIZE:
        sampled_image_filename_list = balanced_df['image_filepath'].sample(
            NORMALIZATION_SAMPLING_SIZE, random_state=SEED
        ).tolist()
    else:
        sampled_image_filename_list = balanced_df['image_filepath'].tolist()

    sampled_image_arraylist = []
    image_fileext = None
    image_size = None

    for image_filename in sampled_image_filename_list:
        img = Image.open(image_filename).convert('RGB')
        img = np.array(img).astype(np.float32)

        assert img.shape[0] == img.shape[1], \
            f"Image {image_filename} is not square: {img.shape}"
        assert image_size is None or image_size == img.shape[0], \
            "All images must have the same size."
        image_size = img.shape[0]

        sampled_image_arraylist.append(img.reshape((1, *img.shape)))

        _, fileext = os.path.splitext(image_filename)
        assert image_fileext is None or image_fileext == fileext
        image_fileext = fileext

    sampled_image_arraylist_concat = np.concatenate(sampled_image_arraylist)
    sampled_image_arraylist_concat = sampled_image_arraylist_concat.astype(np.float32)
    sampled_image_arraylist_concat /= 255.0

    image_mean = sampled_image_arraylist_concat.mean(axis=(0, 1, 2))
    image_std = (sampled_image_arraylist_concat - image_mean).std(axis=(0, 1, 2))

    # -----------------------
    # Build model
    # -----------------------
    model_requires_224 = False
    model, model_requires_224 = build_model(
        opt.model, num_classes=len(cls_list),
        image_size=image_size,
        model_requires_224_flag=model_requires_224
    )

    # Load pretrained checkpoint if provided (before DataParallel)
    if opt.pretrained is not None:
        print(f"Loading pretrained weights from {opt.pretrained}")
        checkpoint = torch.load(opt.pretrained, map_location='cpu')
        state_dict = checkpoint.get('model_state', checkpoint)
        state_dict = strip_module_prefix_if_present(state_dict)
        model.load_state_dict(state_dict, strict=True)
        print("Pretrained weights loaded.")

    # Multi-GPU: DataParallel
    if torch.cuda.is_available():
        if torch.cuda.device_count() > 1:
            print(f"Using {torch.cuda.device_count()} GPUs with DataParallel.")
            model = nn.DataParallel(model)
    model = model.to(device)

    # -----------------------
    # Split train / validation
    # -----------------------
    balanced_df = balanced_df.sample(frac=1, random_state=SEED).reset_index(drop=True)
    train_df = balanced_df.sample(frac=0.9, random_state=SEED)
    validation_df = balanced_df.drop(train_df.index)

    # Datasets & loaders
    train_dataset = SingleCellImageDataset(
        train_df,
        image_mean=image_mean,
        image_std=image_std,
        model_requires_224=model_requires_224,
        train=True
    )

    validation_dataset = SingleCellImageDataset(
        validation_df,
        image_mean=image_mean,
        image_std=image_std,
        model_requires_224=model_requires_224,
        train=False
    )

    train_dataloader = DataLoader(
        train_dataset,
        batch_size=opt.batch_size,
        shuffle=True,
        num_workers=opt.num_workers,
        pin_memory=torch.cuda.is_available(),
        persistent_workers=opt.num_workers > 0
    )

    val_dataloader = DataLoader(
        validation_dataset,
        batch_size=opt.batch_size,
        shuffle=False,
        num_workers=opt.num_workers,
        pin_memory=torch.cuda.is_available(),
        persistent_workers=opt.num_workers > 0
    )

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

    print("\nTraining configuration:")
    print(param_json_data)
    print(f"\nTrain samples: {len(train_dataset)}, "
          f"Val samples: {len(validation_dataset)}, "
          f"Classes: {len(cls_list)}\n")

    # -----------------------
    # Loss, optimizer, scheduler
    # -----------------------
    criterion = nn.CrossEntropyLoss()
    optimizer = torch.optim.Adam(
        model.parameters(),
        lr=opt.learning_rate,
        weight_decay=opt.weight_decay
    )
    scheduler = ReduceLROnPlateau(
        optimizer, mode='min',
        factor=0.1, patience=5,
        threshold=0.0001, threshold_mode='rel',
        cooldown=0, min_lr=0, eps=1e-08
    )

    use_amp = opt.amp and torch.cuda.is_available()
    scaler = torch.cuda.amp.GradScaler(enabled=use_amp)

    # TensorBoard graph (optional, safe for DataParallel by using .module)
    if tensorboard_writer is not None:
        try:
            inputs, labels = next(iter(train_dataloader))
            inputs = inputs.to(device)
            graph_model = model.module if isinstance(model, nn.DataParallel) else model
            tensorboard_writer.add_graph(graph_model, inputs)
        except Exception as e:
            print(f"Warning: could not add model graph to TensorBoard: {e}")

    # -----------------------
    # Training loop
    # -----------------------
    last_cls_acc = 0.0
    best_ckpt_path = None

    for epoch in range(opt.n_epochs):
        print(f"\nEpoch {epoch + 1}/{opt.n_epochs}")

        # ---- Train ----
        model.train()
        running_train_loss = 0.0
        train_correct = 0
        train_total = 0

        train_bar = tqdm(train_dataloader, desc="Training", leave=False)
        for inputs, labels in train_bar:
            inputs = inputs.to(device, non_blocking=True)
            labels = labels.to(device, non_blocking=True)

            optimizer.zero_grad(set_to_none=True)

            with torch.cuda.amp.autocast(enabled=use_amp):
                outputs = model(inputs)
                loss = criterion(outputs, labels)

            # backward
            if use_amp:
                scaler.scale(loss).backward()
                scaler.step(optimizer)
                scaler.update()
            else:
                loss.backward()
                optimizer.step()

            # stats
            batch_size = labels.size(0)
            running_train_loss += loss.item() * batch_size
            _, predicted = torch.max(outputs.data, 1)
            train_total += batch_size
            train_correct += (predicted == labels).sum().item()

            train_bar.set_postfix(loss=loss.item())

        avg_train_loss = running_train_loss / max(1, train_total)
        train_acc = train_correct / max(1, train_total)

        # ---- Validation ----
        model.eval()
        running_val_loss = 0.0
        correct = 0
        total = 0
        y_pred = []
        y_true = []

        with torch.no_grad():
            val_bar = tqdm(val_dataloader, desc="Validation", leave=False)
            for images, labels in val_bar:
                images = images.to(device, non_blocking=True)
                labels = labels.to(device, non_blocking=True)

                with torch.cuda.amp.autocast(enabled=use_amp):
                    outputs = model(images)
                    loss = criterion(outputs, labels)

                batch_size = labels.size(0)
                running_val_loss += loss.item() * batch_size
                _, predicted = torch.max(outputs.data, 1)

                y_pred.extend(predicted.cpu().numpy().tolist())
                y_true.extend(labels.cpu().numpy().tolist())

                total += batch_size
                correct += (predicted == labels).sum().item()

        avg_val_loss = running_val_loss / max(1, total)
        val_cls_acc = correct / max(1, total)

        # Scheduler step on validation loss (more standard)
        scheduler.step(avg_val_loss)
        current_lr = optimizer.param_groups[0]['lr']

        # Logging
        print(
            f"Epoch {epoch + 1}/{opt.n_epochs} | "
            f"train_loss: {avg_train_loss:.4f}, train_acc: {train_acc * 100:.2f}% | "
            f"val_loss: {avg_val_loss:.4f}, val_acc: {val_cls_acc * 100:.2f}% | "
            f"lr: {current_lr:.6g}"
        )

        if tensorboard_writer is not None:
            tensorboard_writer.add_scalar("train_loss", avg_train_loss, epoch)
            tensorboard_writer.add_scalar("train_acc", train_acc * 100, epoch)
            tensorboard_writer.add_scalar("val_loss", avg_val_loss, epoch)
            tensorboard_writer.add_scalar("val_acc", val_cls_acc * 100, epoch)
            tensorboard_writer.add_scalar("lr", current_lr, epoch)
            tensorboard_writer.flush()

        # ---- Save best model + confusion matrix ----
        if val_cls_acc > last_cls_acc:
            last_cls_acc = val_cls_acc
            print(f"New best val_acc: {val_cls_acc * 100:.2f}% at epoch {epoch + 1}")

            # Build normalized confusion matrix (safe for zero rows)
            cf_matrix = confusion_matrix(y_true, y_pred, labels=list(range(len(cls_list))))
            row_sums = cf_matrix.sum(axis=1, keepdims=True)
            row_sums[row_sums == 0] = 1  # avoid division by zero
            norm_cf = cf_matrix / row_sums

            df_cm = pd.DataFrame(
                norm_cf,
                index=[i for i in cls_list],
                columns=[i for i in cls_list]
            )
            fig, ax = plt.subplots(figsize=(12, 7))
            sns.heatmap(df_cm, annot=True, ax=ax)
            ax.set_xticklabels(ax.get_xticklabels(), rotation=45, ha="right")
            fig.tight_layout()
            
            if tensorboard_writer is not None:
                tensorboard_writer.add_figure(
                    "confusion_matrix/val_best",
                    fig,
                    global_step=epoch
                )

            # cm_path = os.path.join(
            #     opt.output_folder,
            #     f"{opt.model_name}-{epoch}-{100 * val_cls_acc:.1f}-confusion_matrix.png"
            # )
            # plt.savefig(cm_path, bbox_inches='tight')
            plt.close(fig)
            # print(f"Confusion matrix saved to {cm_path}")

            # Save checkpoint (always save underlying model, not DataParallel wrapper)
            base_model = model.module if isinstance(model, nn.DataParallel) else model
            ckpt_path = os.path.join(
                opt.output_folder,
                f"{opt.model_name}-{epoch}-{100 * val_cls_acc:.1f}.ckpt.pt"
            )
            torch.save(
                {
                    'model_state': base_model.state_dict(),
                    'parameters': param_json_data
                },
                ckpt_path
            )
            best_ckpt_path = ckpt_path
            print(f"Best checkpoint saved to {ckpt_path}")

    # -----------------------
    # Final save + final confusion matrix
    # -----------------------
    base_model = model.module if isinstance(model, nn.DataParallel) else model
    final_ckpt_path = os.path.join(opt.output_folder, opt.model_name + ".final.pt")
    torch.save(
        {
            'model_state': base_model.state_dict(),
            'parameters': param_json_data
        },
        final_ckpt_path
    )
    print(f"\nFinal model saved to {final_ckpt_path}")
    if best_ckpt_path is not None:
        print(f"Best checkpoint during training: {best_ckpt_path}")

    # Final confusion matrix on validation set
    y_pred = []
    y_true = []
    model.eval()
    with torch.no_grad():
        for images, labels in val_dataloader:
            images = images.to(device, non_blocking=True)
            labels = labels.to(device, non_blocking=True)
            with torch.cuda.amp.autocast(enabled=use_amp):
                outputs = model(images)
            _, predicted = torch.max(outputs.data, 1)
            y_pred.extend(predicted.cpu().numpy().tolist())
            y_true.extend(labels.cpu().numpy().tolist())

    cf_matrix = confusion_matrix(y_true, y_pred, labels=list(range(len(cls_list))))
    row_sums = cf_matrix.sum(axis=1, keepdims=True)
    row_sums[row_sums == 0] = 1
    norm_cf = cf_matrix / row_sums

    df_cm = pd.DataFrame(
        norm_cf,
        index=[i for i in cls_list],
        columns=[i for i in cls_list]
    )
    fig, ax = plt.subplots(figsize=(12, 7))
    sns.heatmap(df_cm, annot=True, ax=ax)
    ax.set_xticklabels(ax.get_xticklabels(), rotation=45, ha="right")
    fig.tight_layout()
    
    if tensorboard_writer is not None:
        tensorboard_writer.add_figure(
            "confusion_matrix/val_final",
            fig,
            global_step=opt.n_epochs
        )

    # final_cm_path = os.path.join(opt.output_folder, opt.model_name + '-confusion_matrix.png')
    # plt.savefig(final_cm_path, bbox_inches='tight')
    plt.close(fig)
    # print(f"Final confusion matrix saved to {final_cm_path}")

    if tensorboard_writer is not None:
        tensorboard_writer.close()

    if opt.pause_on_finish:
        input("Training Done! Press Enter to continue...")


if __name__ == '__main__':
    train(opt)
