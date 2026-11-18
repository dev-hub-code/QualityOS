output "state_bucket" {
  description = "Put this in each environment's backend.hcl."
  value       = aws_s3_bucket.state.bucket
}

output "repositories" {
  description = "Image repository URLs, by image."
  value       = { for name, repo in aws_ecr_repository.image : name => repo.repository_url }
}
