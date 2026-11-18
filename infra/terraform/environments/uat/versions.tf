terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }

  # The bucket comes from the shared stack: terraform init -backend-config=backend.hcl (see backend.hcl.example).
  backend "s3" {
    key          = "uat/terraform.tfstate"
    region       = "ap-south-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = "ap-south-1"

  default_tags {
    tags = {
      Project     = "qualityos"
      Environment = "uat"
      ManagedBy   = "terraform"
    }
  }
}
