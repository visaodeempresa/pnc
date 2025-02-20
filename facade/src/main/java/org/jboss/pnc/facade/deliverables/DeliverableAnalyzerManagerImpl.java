/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014-2022 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.facade.deliverables;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.transaction.Transactional;

import lombok.extern.slf4j.Slf4j;
import org.jboss.pnc.api.deliverablesanalyzer.dto.*;
import org.jboss.pnc.api.dto.Request;
import org.jboss.pnc.api.enums.OperationResult;
import org.jboss.pnc.api.enums.ProgressStatus;
import org.jboss.pnc.bpm.RestConnector;
import org.jboss.pnc.bpm.model.AnalyzeDeliverablesBpmRequest;
import org.jboss.pnc.bpm.task.AnalyzeDeliverablesTask;
import org.jboss.pnc.common.json.moduleconfig.BpmModuleConfig;
import org.jboss.pnc.dto.DeliverableAnalyzerOperation;
import org.jboss.pnc.enums.ArtifactQuality;
import org.jboss.pnc.enums.RepositoryType;
import org.jboss.pnc.facade.OperationsManager;
import org.jboss.pnc.facade.impl.OperationChangedEvent;
import org.jboss.pnc.facade.util.UserService;
import org.jboss.pnc.mapper.api.ArtifactMapper;
import org.jboss.pnc.mapper.api.DeliverableAnalyzerOperationMapper;
import org.jboss.pnc.model.Base32LongID;
import org.jboss.pnc.model.ProductMilestone;
import org.jboss.pnc.model.TargetRepository;
import org.jboss.pnc.spi.datastore.predicates.ArtifactPredicates;
import org.jboss.pnc.spi.datastore.repositories.ArtifactRepository;
import org.jboss.pnc.spi.datastore.repositories.DeliverableAnalyzerOperationRepository;
import org.jboss.pnc.spi.datastore.repositories.ProductMilestoneRepository;
import org.jboss.pnc.spi.datastore.repositories.TargetRepositoryRepository;
import org.jboss.pnc.spi.exception.ProcessManagerException;

import static org.jboss.pnc.constants.ReposiotryIdentifier.DISTRIBUTION_ARCHIVE;
import static org.jboss.pnc.constants.ReposiotryIdentifier.INDY_MAVEN;

/**
 *
 * @author jbrazdil
 */
@ApplicationScoped
@Slf4j
public class DeliverableAnalyzerManagerImpl implements org.jboss.pnc.facade.DeliverableAnalyzerManager {
    private static final String KOJI_PATH_MAVEN_PREFIX = "/api/content/maven/remote/koji-";
    public static final String URL_PARAMETER_PREFIX = "url-";

    @Inject
    private ProductMilestoneRepository milestoneRepository;
    @Inject
    private ArtifactRepository artifactRepository;
    @Inject
    private TargetRepositoryRepository targetRepositoryRepository;
    @Inject
    private DeliverableAnalyzerOperationRepository deliverableAnalyzerOperationRepository;
    @Inject
    private ArtifactMapper artifactMapper;
    @Inject
    private OperationsManager operationsManager;
    @Inject
    private UserService userService;
    @Inject
    private BpmModuleConfig bpmConfig;
    @Inject
    private DeliverableAnalyzerOperationMapper deliverableAnalyzerOperationMapper;
    @Inject
    private Event<DeliverableAnalysisStatusChangedEvent> analysisStatusChangedEventNotifier;

    @Override
    public DeliverableAnalyzerOperation analyzeDeliverables(String id, List<String> sourcesLink) {
        int i = 1;
        Map<String, String> inputParams = new HashMap<>();
        for (String url : sourcesLink) {
            inputParams.put(URL_PARAMETER_PREFIX + (i++), url);
        }

        Base32LongID operationId = operationsManager.newDeliverableAnalyzerOperation(id, inputParams).getId();

        try {
            startAnalysis(id, sourcesLink, operationId);
            return deliverableAnalyzerOperationMapper.toDTO(
                    (org.jboss.pnc.model.DeliverableAnalyzerOperation) operationsManager
                            .updateProgress(operationId, ProgressStatus.IN_PROGRESS));
        } catch (RuntimeException ex) {
            operationsManager.setResult(operationId, OperationResult.SYSTEM_ERROR);
            throw ex;
        }
    }

    private void processDeliverables(int milestoneId, Collection<Build> builds, String distributionUrl) {
        ProductMilestone milestone = milestoneRepository.queryById(milestoneId);
        for (Build build : builds) {
            Function<Artifact, org.jboss.pnc.model.Artifact> artifactParser;
            if (build.getBuildSystemType() == null) {
                TargetRepository distributionRepository = getDistributionRepository(distributionUrl);
                artifactParser = art -> findOrCreateArtifact(art, distributionRepository);
            } else {
                switch (build.getBuildSystemType()) {
                    case PNC:
                        artifactParser = this::getPncArtifact;
                        break;
                    case BREW:
                        TargetRepository brewRepository = getBrewRepository(build);
                        artifactParser = art -> findOrCreateArtifact(assertBrewArtifacts(art), brewRepository);
                        break;
                    default:
                        throw new UnsupportedOperationException(
                                "Unknown build system type " + build.getBuildSystemType());
                }
            }
            build.getArtifacts().stream().map(artifactParser).forEach(milestone::addDeliveredArtifact);
        }
        milestone.setDeliveredArtifactsImporter(userService.currentUser());
    }

    @Override
    @Transactional
    public void completeAnalysis(int milestoneId, List<FinderResult> results) {
        for (FinderResult finderResult : results) {
            processDeliverables(milestoneId, finderResult.getBuilds(), finderResult.getUrl().toString());
        }
    }

    @SuppressWarnings("unchecked")
    private org.jboss.pnc.model.Artifact findOrCreateArtifact(Artifact art, TargetRepository targetRepo) {
        org.jboss.pnc.model.Artifact artifact = mapArtifact(art);
        // find
        org.jboss.pnc.model.Artifact dbArtifact = artifactRepository.queryByPredicates(
                ArtifactPredicates.withIdentifierAndSha256(artifact.getIdentifier(), artifact.getSha256()),
                ArtifactPredicates.withTargetRepositoryId(targetRepo.getId()));
        if (dbArtifact != null) {
            return dbArtifact;
        }

        // create
        artifact.setTargetRepository(targetRepo);
        org.jboss.pnc.model.Artifact savedArtifact = artifactRepository.save(artifact);
        targetRepo.getArtifacts().add(savedArtifact);
        return savedArtifact;
    }

    private org.jboss.pnc.model.Artifact getPncArtifact(Artifact art) {
        org.jboss.pnc.model.Artifact artifact = artifactRepository
                .queryById(artifactMapper.getIdMapper().toEntity(art.getPncId()));
        if (artifact == null) {
            throw new IllegalArgumentException("PNC artifact with id " + art.getPncId() + " doesn't exist.");
        }
        return artifact;
    }

    private org.jboss.pnc.model.Artifact mapArtifact(Artifact art) {
        org.jboss.pnc.model.Artifact.Builder builder = org.jboss.pnc.model.Artifact.builder();
        builder.md5(art.getMd5());
        builder.sha1(art.getSha1());
        builder.sha256(art.getSha256());
        builder.size(art.getSize());
        builder.filename(art.getFilename());
        if (art.getArtifactType() == null) {
            builder.identifier(art.getFilename());
        } else
            switch (art.getArtifactType()) {
                case MAVEN:
                    builder.identifier(fill((MavenArtifact) art));
                    break;
                case NPM:
                    builder.identifier(fill((NPMArtifact) art));
                    break;
            }
        if (art.isBuiltFromSource()) {
            builder.artifactQuality(ArtifactQuality.NEW);
        } else {
            builder.artifactQuality(ArtifactQuality.IMPORTED);
        }

        return builder.build();
    }

    private String fill(MavenArtifact mavenArtifact) {
        return Arrays
                .asList(
                        mavenArtifact.getGroupId(),
                        mavenArtifact.getArtifactId(),
                        mavenArtifact.getType(),
                        mavenArtifact.getVersion(),
                        mavenArtifact.getClassifier())
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.joining(":"));
    }

    private String fill(NPMArtifact mavenArtifact) {
        return mavenArtifact.getName() + ":" + mavenArtifact.getVersion();
    }

    private TargetRepository getBrewRepository(Build build) {
        String path = KOJI_PATH_MAVEN_PREFIX + build.getBrewNVR();
        TargetRepository tr = targetRepositoryRepository.queryByIdentifierAndPath(INDY_MAVEN, path);
        if (tr == null) {
            tr = createRepository(path, INDY_MAVEN, RepositoryType.MAVEN);
        }
        return tr;
    }

    private TargetRepository getDistributionRepository(String distURL) {
        TargetRepository tr = targetRepositoryRepository.queryByIdentifierAndPath(DISTRIBUTION_ARCHIVE, distURL);
        if (tr == null) {
            tr = createRepository(distURL, DISTRIBUTION_ARCHIVE, RepositoryType.DISTRIBUTION_ARCHIVE);
        }
        return tr;
    }

    private TargetRepository createRepository(String path, String identifier, RepositoryType type) {
        TargetRepository tr = TargetRepository.newBuilder()
                .temporaryRepo(false)
                .identifier(identifier)
                .repositoryPath(path)
                .repositoryType(type)
                .build();
        return targetRepositoryRepository.save(tr);
    }

    private Artifact assertBrewArtifacts(Artifact artifact) {
        if (!(artifact.getArtifactType() == null || artifact.getArtifactType() == ArtifactType.MAVEN)) {
            throw new IllegalArgumentException(
                    "Brew artifacts are expected to be either MAVEN or unknown, artifact " + artifact + " is "
                            + artifact.getArtifactType());
        }
        return artifact;
    }

    private void startAnalysis(String milestoneId, List<String> sourcesLink, Base32LongID operationId) {
        Request callback = operationsManager.getOperationCallback(operationId);
        String id = operationId.getId();
        try (RestConnector restConnector = new RestConnector(bpmConfig)) {
            AnalyzeDeliverablesBpmRequest bpmRequest = new AnalyzeDeliverablesBpmRequest(id, milestoneId, sourcesLink);
            AnalyzeDeliverablesTask analyzeTask = new AnalyzeDeliverablesTask(bpmRequest, callback);

            restConnector.startProcess(
                    bpmConfig.getAnalyzeDeliverablesBpmProcessId(),
                    analyzeTask,
                    id,
                    userService.currentUserToken());

            DeliverableAnalysisStatusChangedEvent analysisStatusChanged = DefaultDeliverableAnalysisStatusChangedEvent
                    .started(id, milestoneId, sourcesLink);
            analysisStatusChangedEventNotifier.fire(analysisStatusChanged);
        } catch (ProcessManagerException e) {
            log.error("Error trying to start analysis of deliverables task for milestone: {}", milestoneId, e);
            throw new RuntimeException(e);
        }
    }

    public void observeEvent(@Observes OperationChangedEvent event) {
        if (event.getOperationClass() != org.jboss.pnc.model.DeliverableAnalyzerOperation.class) {
            return;
        }
        log.debug("Observed deliverable analysis operation status changed event {}.", event);
        if (event.getStatus() == ProgressStatus.FINISHED && event.getPreviousStatus() != ProgressStatus.FINISHED) {
            org.jboss.pnc.model.DeliverableAnalyzerOperation operation = deliverableAnalyzerOperationRepository
                    .queryById(event.getId());
            onDeliverableAnalysisFinished(operation);
        }
    }

    private void onDeliverableAnalysisFinished(org.jboss.pnc.model.DeliverableAnalyzerOperation operation) {
        List<String> sourcesLinks = operation.getOperationParameters()
                .entrySet()
                .stream()
                .filter(e -> e.getKey().startsWith(URL_PARAMETER_PREFIX))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
        DeliverableAnalysisStatusChangedEvent analysisStatusChanged = DefaultDeliverableAnalysisStatusChangedEvent
                .finished(
                        operation.getId().getId(),
                        operation.getProductMilestone().getId().toString(),
                        operation.getResult(),
                        sourcesLinks);
        analysisStatusChangedEventNotifier.fire(analysisStatusChanged);
    }

}
