NAME = "com_github_davido_bazlets"

def load_bazlets(
    tag,
    local_path = None
  ):
  if not local_path:
      native.git_repository(
          name = NAME,
          remote = "https://github.com/davido/bazlets.git",
          tag = tag,
      )
  else:
      native.local_repository(
          name = NAME,
          path = local_path,
      )
