interface ActuatorInfo {
  git: ActuatorGit,
  build: ActuatorBuild
}

interface ActuatorGit {
  commit: ActuatorGitCommit,
  branch: string
}

interface ActuatorGitCommit {
  time: Date,
  id: string
}

interface ActuatorBuild {
  version: string,
  artifact: string,
  name: string,
  group: string,
  time: Date,
  // ShabbyFork: the fork build number, absent on an upstream build
  forkBuild?: string
}
