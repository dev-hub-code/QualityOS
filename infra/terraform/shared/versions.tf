terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }

  # This stack creates the bucket every other stack keeps its state in, so its own state stays local, on the
  # machine that applies it. It holds no secrets. Keep a copy of terraform.tfstate somewhere safe.
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "qualityos"
      Stack     = "shared"
      ManagedBy = "terraform"
    }
  }
}
