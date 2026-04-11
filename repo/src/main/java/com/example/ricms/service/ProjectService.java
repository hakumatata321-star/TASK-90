package com.example.ricms.service;

import com.example.ricms.domain.entity.Project;
import com.example.ricms.dto.request.ProjectCreateRequest;
import com.example.ricms.dto.response.ProjectResponse;
import com.example.ricms.exception.AppException;
import com.example.ricms.repository.ProjectRepository;
import com.example.ricms.security.PermissionEnforcer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final PermissionEnforcer permissionEnforcer;

    @Transactional
    public ProjectResponse createProject(ProjectCreateRequest request) {
        permissionEnforcer.require("PROJECT", "WRITE");
        Project project = Project.builder()
                .name(request.getName())
                .description(request.getDescription())
                .build();
        project = projectRepository.save(project);
        return toResponse(project);
    }

    public ProjectResponse getProject(UUID projectId) {
        permissionEnforcer.require("PROJECT", "READ");
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new AppException("PROJECT_NOT_FOUND", "Project not found", HttpStatus.NOT_FOUND));
        return toResponse(project);
    }

    public ProjectResponse toResponse(Project project) {
        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .createdAt(project.getCreatedAt())
                .build();
    }
}
