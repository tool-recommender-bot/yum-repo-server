import os

from yum_repo_server.api.services.repoConfigService import RepoConfigService

class RepoContentService(object):
    """
        Service to retrieve information about the content of a repository.
    """

    repoConfigService = RepoConfigService()

    def list_packages(self, repository_name):
        """
            @return: a list of tuples (first element is the architecture name, second element is the package file name)
        """
        repository_path = self.repoConfigService.getStaticRepoDir(repository_name)


        files_in_repository = os.listdir(repository_path)
        available_architectures = []

        for potential_dir in files_in_repository:
            if potential_dir != "repodata":
                if os.path.isdir(os.path.join(repository_path, potential_dir)):
                    available_architectures.append(potential_dir)

        packages_in_repository = []

        for architecture in available_architectures:
            architecture_path = os.path.join(repository_path, architecture)
            packages_in_architecture_dir = os.listdir(architecture_path)

            for package in packages_in_architecture_dir:
                package_path = os.path.join(repository_path, architecture, package)
                packages_in_repository.append((architecture, package_path))

        return packages_in_repository