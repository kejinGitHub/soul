/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dromara.soul.admin.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.dromara.soul.admin.dto.RuleConditionDTO;
import org.dromara.soul.admin.dto.RuleDTO;
import org.dromara.soul.admin.dto.SelectorConditionDTO;
import org.dromara.soul.admin.dto.SelectorDTO;
import org.dromara.soul.admin.entity.MetaDataDO;
import org.dromara.soul.admin.entity.PluginDO;
import org.dromara.soul.admin.entity.RuleDO;
import org.dromara.soul.admin.entity.SelectorDO;
import org.dromara.soul.admin.listener.DataChangedEvent;
import org.dromara.soul.admin.mapper.MetaDataMapper;
import org.dromara.soul.admin.mapper.PluginMapper;
import org.dromara.soul.admin.mapper.RuleMapper;
import org.dromara.soul.admin.mapper.SelectorMapper;
import org.dromara.soul.admin.service.RuleService;
import org.dromara.soul.admin.service.SelectorService;
import org.dromara.soul.admin.service.SoulClientRegisterService;
import org.dromara.soul.admin.transfer.MetaDataTransfer;
import org.dromara.soul.admin.utils.SoulResultMessage;
import org.dromara.soul.common.dto.SelectorData;
import org.dromara.soul.common.dto.convert.DivideUpstream;
import org.dromara.soul.common.dto.convert.rule.RuleHandle;
import org.dromara.soul.common.dto.convert.rule.RuleHandleFactory;
import org.dromara.soul.common.dto.convert.selector.SpringCloudSelectorHandle;
import org.dromara.soul.common.enums.ConfigGroupEnum;
import org.dromara.soul.common.enums.DataEventTypeEnum;
import org.dromara.soul.common.enums.MatchModeEnum;
import org.dromara.soul.common.enums.OperatorEnum;
import org.dromara.soul.common.enums.ParamTypeEnum;
import org.dromara.soul.common.enums.PluginEnum;
import org.dromara.soul.common.enums.RpcTypeEnum;
import org.dromara.soul.common.enums.SelectorTypeEnum;
import org.dromara.soul.common.utils.GsonUtils;
import org.dromara.soul.common.utils.UUIDUtils;
import org.dromara.soul.register.common.dto.MetaDataRegisterDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The type Soul client register service.
 *
 * @author nuo-promise
 */
@Service("soulClientRegisterService")
public class SoulClientRegisterServiceImpl implements SoulClientRegisterService {
    
    private static final String CONTEXT_PATH_NAME_PREFIX = "/context-path";

    private final MetaDataMapper metaDataMapper;

    private final ApplicationEventPublisher eventPublisher;

    private final SelectorService selectorService;

    private final RuleService ruleService;

    private final RuleMapper ruleMapper;

    private final UpstreamCheckService upstreamCheckService;

    private final SelectorMapper selectorMapper;

    private final PluginMapper pluginMapper;

    /**
     * Instantiates a new Meta data service.
     *
     * @param metaDataMapper       the meta data mapper
     * @param eventPublisher       the event publisher
     * @param selectorService      the selector service
     * @param ruleService          the rule service
     * @param ruleMapper           the rule mapper
     * @param upstreamCheckService the upstream check service
     * @param selectorMapper       the selector mapper
     * @param pluginMapper         the plugin mapper
     */
    @Autowired(required = false)
    public SoulClientRegisterServiceImpl(final MetaDataMapper metaDataMapper,
                                         final ApplicationEventPublisher eventPublisher,
                                         final SelectorService selectorService,
                                         final RuleService ruleService,
                                         final RuleMapper ruleMapper,
                                         final UpstreamCheckService upstreamCheckService,
                                         final SelectorMapper selectorMapper,
                                         final PluginMapper pluginMapper) {
        this.metaDataMapper = metaDataMapper;
        this.eventPublisher = eventPublisher;
        this.selectorService = selectorService;
        this.ruleService = ruleService;
        this.ruleMapper = ruleMapper;
        this.upstreamCheckService = upstreamCheckService;
        this.selectorMapper = selectorMapper;
        this.pluginMapper = pluginMapper;
    }

    @Override
    @Transactional
    public synchronized String registerSpringMvc(final MetaDataRegisterDTO dto) {
        if (dto.isRegisterMetaData()) {
            MetaDataDO exist = metaDataMapper.findByPath(dto.getPath());
            if (Objects.isNull(exist)) {
                saveSpringMvcMetaData(dto);
            }
        }
        String selectorId = handlerSpringMvcSelector(dto);
        handlerSpringMvcRule(selectorId, dto);
        String contextPath = dto.getContextPath();
        if (StringUtils.isNotEmpty(contextPath)) {
            //register context path plugin
            registerContextPathPlugin(contextPath);
        }
        return SoulResultMessage.SUCCESS;
    }

    @Override
    @Transactional
    public synchronized String registerSpringCloud(final MetaDataRegisterDTO dto) {
        MetaDataDO metaDataDO = metaDataMapper.findByPath(dto.getContextPath() + "/**");
        if (Objects.isNull(metaDataDO)) {
            saveSpringCloudMetaData(dto);
        }
        String selectorId = handlerSpringCloudSelector(dto);
        handlerSpringCloudRule(selectorId, dto);
        String contextPath = dto.getContextPath();
        if (StringUtils.isNotEmpty(contextPath)) {
            //register context path plugin
            registerContextPathPlugin(contextPath);
        }
        return SoulResultMessage.SUCCESS;
    }
    
    private void registerContextPathPlugin(final String contextPath) {
        String name = CONTEXT_PATH_NAME_PREFIX + contextPath;
        SelectorDO selectorDO = selectorService.findByName(name);
        if (Objects.isNull(selectorDO)) {
            String contextPathSelectorId = registerContextPathSelector(contextPath, name);
            RuleDO ruleDO = ruleMapper.findByName(name);
            if (Objects.isNull(ruleDO)) {
                registerRule(contextPathSelectorId, contextPath + "/**", PluginEnum.CONTEXTPATH_MAPPING.getName(), name);
            }
        }
    }

    @Override
    @Transactional
    public synchronized String registerDubbo(final MetaDataRegisterDTO dto) {
        MetaDataDO exist = metaDataMapper.findByPath(dto.getPath());
        saveOrUpdateMetaData(exist, dto);
        String selectorId = handlerDubboSelector(dto);
        handlerDubboRule(selectorId, dto);
        return SoulResultMessage.SUCCESS;
    }

    @Override
    public synchronized String registerSofa(final MetaDataRegisterDTO dto) {
        MetaDataDO metaDataDO = metaDataMapper.findByPath(dto.getPath());
        if (Objects.nonNull(metaDataDO)
                && (!metaDataDO.getMethodName().equals(dto.getMethodName())
                || !metaDataDO.getServiceName().equals(dto.getServiceName()))) {
            return "you path already exist!";
        }
        final MetaDataDO exist = metaDataMapper.findByServiceNameAndMethod(dto.getServiceName(), dto.getMethodName());
        saveOrUpdateMetaData(exist, dto);
        String selectorId = handlerSofaSelector(dto);
        handlerSofaRule(selectorId, dto, exist);
        return SoulResultMessage.SUCCESS;
    }

    @Override
    public synchronized String registerTars(final MetaDataRegisterDTO dto) {
        MetaDataDO byPath = metaDataMapper.findByPath(dto.getPath());
        if (Objects.nonNull(byPath)
                && (!byPath.getMethodName().equals(dto.getMethodName())
                || !byPath.getServiceName().equals(dto.getServiceName()))) {
            return "you path already exist!";
        }
        final MetaDataDO exist = metaDataMapper.findByServiceNameAndMethod(dto.getServiceName(), dto.getMethodName());
        saveOrUpdateMetaData(exist, dto);
        String selectorId = handlerTarsSelector(dto);
        handlerTarsRule(selectorId, dto, exist);
        return SoulResultMessage.SUCCESS;
    }

    @Override
    public synchronized String registerGrpc(final MetaDataRegisterDTO dto) {
        MetaDataDO exist = metaDataMapper.findByPath(dto.getPath());
        saveOrUpdateMetaData(exist, dto);
        String selectorId = handlerGrpcSelector(dto);
        handlerGrpcRule(selectorId, dto, exist);
        return SoulResultMessage.SUCCESS;
    }
    
    private String handlerDubboSelector(final MetaDataRegisterDTO metaDataDTO) {
        return getString(metaDataDTO);
    }
    
    private String getString(final MetaDataRegisterDTO metaDataDTO) {
        SelectorDO selectorDO = selectorService.findByName(metaDataDTO.getContextPath());
        String selectorId;
        if (Objects.isNull(selectorDO)) {
            selectorId = registerSelector(metaDataDTO.getContextPath(), metaDataDTO.getRpcType(), metaDataDTO.getAppName(), "");
        } else {
            selectorId = selectorDO.getId();
        }
        return selectorId;
    }
    
    private void handlerDubboRule(final String selectorId, final MetaDataRegisterDTO metaDataDTO) {
        RuleDO existRule = ruleMapper.findByName(metaDataDTO.getPath());
        if (Objects.isNull(existRule)) {
            registerRule(selectorId, metaDataDTO.getPath(), PluginEnum.DUBBO.getName(), metaDataDTO.getRuleName());
        }
    }

    private String handlerTarsSelector(final MetaDataRegisterDTO metaDataDTO) {
        return getString(metaDataDTO);
    }

    private void handlerTarsRule(final String selectorId, final MetaDataRegisterDTO metaDataDTO, final MetaDataDO exist) {
        RuleDO existRule = ruleMapper.findByName(metaDataDTO.getPath());
        if (Objects.isNull(exist) || Objects.isNull(existRule)) {
            registerRule(selectorId, metaDataDTO.getPath(), PluginEnum.TARS.getName(), metaDataDTO.getRuleName());
        }
    }

    private String handlerSofaSelector(final MetaDataRegisterDTO metaDataDTO) {
        return getString(metaDataDTO);
    }

    private void handlerSofaRule(final String selectorId, final MetaDataRegisterDTO metaDataDTO, final MetaDataDO exist) {
        RuleDO existRule = ruleMapper.findByName(metaDataDTO.getPath());
        if (Objects.isNull(exist) || Objects.isNull(existRule)) {
            registerRule(selectorId, metaDataDTO.getPath(), PluginEnum.SOFA.getName(), metaDataDTO.getRuleName());
        }
    }

    private String handlerGrpcSelector(final MetaDataRegisterDTO metaDataDTO) {
        return getString(metaDataDTO);
    }

    private void handlerGrpcRule(final String selectorId, final MetaDataRegisterDTO metaDataDTO, final MetaDataDO exist) {
        RuleDO existRule = ruleMapper.findByName(metaDataDTO.getPath());
        if (Objects.isNull(exist) || Objects.isNull(existRule)) {
            registerRule(selectorId, metaDataDTO.getPath(), PluginEnum.GRPC.getName(), metaDataDTO.getRuleName());
        }
    }

    private void saveSpringMvcMetaData(final MetaDataRegisterDTO dto) {
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        MetaDataDO metaDataDO = MetaDataDO.builder()
                .appName(dto.getAppName())
                .path(dto.getPath())
                .pathDesc(dto.getPathDesc())
                .rpcType(dto.getRpcType())
                .enabled(dto.isEnabled())
                .id(UUIDUtils.getInstance().generateShortUuid())
                .dateCreated(currentTime)
                .dateUpdated(currentTime)
                .build();
        metaDataMapper.insert(metaDataDO);
        // publish AppAuthData's event
        eventPublisher.publishEvent(new DataChangedEvent(ConfigGroupEnum.META_DATA, DataEventTypeEnum.CREATE,
                Collections.singletonList(MetaDataTransfer.INSTANCE.mapToData(metaDataDO))));
    }

    private void saveSpringCloudMetaData(final MetaDataRegisterDTO dto) {
        Timestamp currentTime = new Timestamp(System.currentTimeMillis());
        MetaDataDO metaDataDO = MetaDataDO.builder()
                .appName(dto.getAppName())
                .path(dto.getContextPath() + "/**")
                .pathDesc(dto.getAppName() + "spring cloud meta data info")
                .serviceName(dto.getAppName())
                .methodName(dto.getContextPath())
                .rpcType(dto.getRpcType())
                .enabled(dto.isEnabled())
                .id(UUIDUtils.getInstance().generateShortUuid())
                .dateCreated(currentTime)
                .dateUpdated(currentTime)
                .build();
        metaDataMapper.insert(metaDataDO);
        // publish AppAuthData's event
        eventPublisher.publishEvent(new DataChangedEvent(ConfigGroupEnum.META_DATA, DataEventTypeEnum.CREATE,
                Collections.singletonList(MetaDataTransfer.INSTANCE.mapToData(metaDataDO))));
    }

    private void saveOrUpdateMetaData(final MetaDataDO exist, final MetaDataRegisterDTO metaDataDTO) {
        DataEventTypeEnum eventType;
        MetaDataDO metaDataDO = MetaDataTransfer.INSTANCE.mapRegisterDTOToEntity(metaDataDTO);
        if (Objects.isNull(exist)) {
            Timestamp currentTime = new Timestamp(System.currentTimeMillis());
            metaDataDO.setId(UUIDUtils.getInstance().generateShortUuid());
            metaDataDO.setDateCreated(currentTime);
            metaDataDO.setDateUpdated(currentTime);
            metaDataMapper.insert(metaDataDO);
            eventType = DataEventTypeEnum.CREATE;
        } else {
            metaDataDO.setId(exist.getId());
            metaDataMapper.update(metaDataDO);
            eventType = DataEventTypeEnum.UPDATE;
        }
        // publish MetaData's event
        eventPublisher.publishEvent(new DataChangedEvent(ConfigGroupEnum.META_DATA, eventType,
                Collections.singletonList(MetaDataTransfer.INSTANCE.mapRegisterDTOToEntity(metaDataDTO))));
    }

    private String handlerSpringMvcSelector(final MetaDataRegisterDTO dto) {
        String contextPath = dto.getContextPath();
        if (StringUtils.isEmpty(contextPath)) {
            contextPath = buildContextPath(dto.getPath());
        } 
        SelectorDO selectorDO = selectorService.findByName(contextPath);
        String selectorId;
        String uri = String.join(":", dto.getHost(), String.valueOf(dto.getPort()));
        if (Objects.isNull(selectorDO)) {
            selectorId = registerSelector(contextPath, dto.getRpcType(), dto.getAppName(), uri);
        } else {
            selectorId = selectorDO.getId();
            //update upstream
            String handle = selectorDO.getHandle();
            String handleAdd;
            DivideUpstream addDivideUpstream = buildDivideUpstream(uri);
            SelectorData selectorData = selectorService.buildByName(contextPath);
            if (StringUtils.isBlank(handle)) {
                handleAdd = GsonUtils.getInstance().toJson(Collections.singletonList(addDivideUpstream));
            } else {
                List<DivideUpstream> exist = GsonUtils.getInstance().fromList(handle, DivideUpstream.class);
                for (DivideUpstream upstream : exist) {
                    if (upstream.getUpstreamUrl().equals(addDivideUpstream.getUpstreamUrl())) {
                        return selectorId;
                    }
                }
                exist.add(addDivideUpstream);
                handleAdd = GsonUtils.getInstance().toJson(exist);
            }
            selectorDO.setHandle(handleAdd);
            selectorData.setHandle(handleAdd);
            // update db
            selectorMapper.updateSelective(selectorDO);
            // submit upstreamCheck
            upstreamCheckService.submit(contextPath, addDivideUpstream);
            // publish change event.
            eventPublisher.publishEvent(new DataChangedEvent(ConfigGroupEnum.SELECTOR, DataEventTypeEnum.UPDATE,
                    Collections.singletonList(selectorData)));
        }
        return selectorId;
    }
    
    private String buildContextPath(final String path) {
        String split = "/";
        String[] splitList = StringUtils.split(path, split);
        if (splitList.length != 0) {
            return split.concat(splitList[0]);
        }
        return split;
    }

    private void handlerSpringMvcRule(final String selectorId, final MetaDataRegisterDTO dto) {
        RuleDO ruleDO = ruleMapper.findByName(dto.getRuleName());
        if (Objects.isNull(ruleDO)) {
            registerRule(selectorId, dto.getPath(), PluginEnum.DIVIDE.getName(), dto.getRuleName());
        }
    }

    private String handlerSpringCloudSelector(final MetaDataRegisterDTO dto) {
        String contextPath = dto.getContextPath();
        if (StringUtils.isEmpty(contextPath)) {
            contextPath = buildContextPath(dto.getPath());
        }
        SelectorDO selectorDO = selectorService.findByName(contextPath);
        if (Objects.isNull(selectorDO)) {
            return registerSelector(contextPath, dto.getRpcType(), dto.getAppName(), "");
        } else {
            return selectorDO.getId();
        }
    }

    private void handlerSpringCloudRule(final String selectorId, final MetaDataRegisterDTO dto) {
        RuleDO ruleDO = ruleMapper.findByName(dto.getRuleName());
        if (Objects.isNull(ruleDO)) {
            registerRule(selectorId, dto.getPath(), PluginEnum.SPRING_CLOUD.getName(), dto.getRuleName());
        }
    }

    private String registerSelector(final String contextPath, final String rpcType, final String appName, final String uri) {
        SelectorDTO selectorDTO = SelectorDTO.builder()
                .name(contextPath)
                .type(SelectorTypeEnum.CUSTOM_FLOW.getCode())
                .matchMode(MatchModeEnum.AND.getCode())
                .enabled(Boolean.TRUE)
                .loged(Boolean.TRUE)
                .continued(Boolean.TRUE)
                .sort(1)
                .build();
        if (RpcTypeEnum.DUBBO.getName().equals(rpcType)) {
            selectorDTO.setPluginId(getPluginId(PluginEnum.DUBBO.getName()));
        } else if (RpcTypeEnum.SPRING_CLOUD.getName().equals(rpcType)) {
            selectorDTO.setPluginId(getPluginId(PluginEnum.SPRING_CLOUD.getName()));
            selectorDTO.setHandle(GsonUtils.getInstance().toJson(buildSpringCloudSelectorHandle(appName)));
        } else if (RpcTypeEnum.SOFA.getName().equals(rpcType)) {
            selectorDTO.setPluginId(getPluginId(PluginEnum.SOFA.getName()));
            selectorDTO.setHandle(appName);
        } else if (RpcTypeEnum.TARS.getName().equals(rpcType)) {
            selectorDTO.setPluginId(getPluginId(PluginEnum.TARS.getName()));
            selectorDTO.setHandle(appName);
        } else if (RpcTypeEnum.GRPC.getName().equals(rpcType)) {
            selectorDTO.setPluginId(getPluginId(PluginEnum.GRPC.getName()));
            selectorDTO.setHandle(appName);
        } else {
            //is divide
            DivideUpstream divideUpstream = buildDivideUpstream(uri);
            String handler = GsonUtils.getInstance().toJson(Collections.singletonList(divideUpstream));
            selectorDTO.setHandle(handler);
            selectorDTO.setPluginId(getPluginId(PluginEnum.DIVIDE.getName()));
            upstreamCheckService.submit(selectorDTO.getName(), divideUpstream);
        }
        selectorDTO.setSelectorConditions(buildDefaultSelectorConditionDTO(contextPath));
        return selectorService.register(selectorDTO);
    }
    
    private String registerContextPathSelector(final String contextPath, final String name) {
        SelectorDTO selectorDTO = SelectorDTO.builder()
                .name(name)
                .type(SelectorTypeEnum.CUSTOM_FLOW.getCode())
                .matchMode(MatchModeEnum.AND.getCode())
                .enabled(Boolean.TRUE)
                .loged(Boolean.TRUE)
                .continued(Boolean.TRUE)
                .sort(1)
                .build();
        selectorDTO.setPluginId(getPluginId(PluginEnum.CONTEXTPATH_MAPPING.getName()));
        selectorDTO.setSelectorConditions(buildDefaultSelectorConditionDTO(contextPath));
        return selectorService.register(selectorDTO);
    }
    
    private List<SelectorConditionDTO> buildDefaultSelectorConditionDTO(final String contextPath) {
        SelectorConditionDTO selectorConditionDTO = new SelectorConditionDTO();
        selectorConditionDTO.setParamType(ParamTypeEnum.URI.getName());
        selectorConditionDTO.setParamName("/");
        selectorConditionDTO.setOperator(OperatorEnum.MATCH.getAlias());
        selectorConditionDTO.setParamValue(contextPath + "/**");
        return Collections.singletonList(selectorConditionDTO);
    }

    private DivideUpstream buildDivideUpstream(final String uri) {
        return DivideUpstream.builder()
                .upstreamHost("localhost")
                .protocol("http://")
                .upstreamUrl(uri)
                .weight(50)
                .build();
    }

    private SpringCloudSelectorHandle buildSpringCloudSelectorHandle(final String serviceId) {
        return SpringCloudSelectorHandle.builder()
                .serviceId(serviceId)
                .build();
    }

    private String getPluginId(final String pluginName) {
        final PluginDO pluginDO = pluginMapper.selectByName(pluginName);
        Objects.requireNonNull(pluginDO);
        return pluginDO.getId();
    }

    private void registerRule(final String selectorId, final String path, final String pluginName, final String ruleName) {
        RuleHandle ruleHandle;
        if (pluginName.equals(PluginEnum.CONTEXTPATH_MAPPING.getName())) {
            ruleHandle = RuleHandleFactory.ruleHandle(pluginName, buildContextPath(path));
        } else {
            ruleHandle = RuleHandleFactory.ruleHandle(pluginName, path);
        }
        RuleDTO ruleDTO = RuleDTO.builder()
                .selectorId(selectorId)
                .name(ruleName)
                .matchMode(MatchModeEnum.AND.getCode())
                .enabled(Boolean.TRUE)
                .loged(Boolean.TRUE)
                .sort(1)
                .handle(ruleHandle.toJson())
                .build();
        RuleConditionDTO ruleConditionDTO = RuleConditionDTO.builder()
                .paramType(ParamTypeEnum.URI.getName())
                .paramName("/")
                .paramValue(path)
                .build();
        if (path.indexOf("*") > 1) {
            ruleConditionDTO.setOperator(OperatorEnum.MATCH.getAlias());
        } else {
            ruleConditionDTO.setOperator(OperatorEnum.EQ.getAlias());
        }
        ruleDTO.setRuleConditions(Collections.singletonList(ruleConditionDTO));
        ruleService.register(ruleDTO);
    }

    @Override
    public String registerURI(final String contextPath, final List<String> uriList) {
        SelectorDO selector = selectorService.findByName(contextPath);
        SelectorData selectorData = selectorService.buildByName(contextPath);
        String handler = GsonUtils.getInstance().toJson(buildDivideUpstreamList(uriList));
        selector.setHandle(handler);
        selectorData.setHandle(handler);
        selectorMapper.updateSelective(selector);
        // publish change event.
        eventPublisher.publishEvent(new DataChangedEvent(ConfigGroupEnum.SELECTOR, DataEventTypeEnum.UPDATE,
                Collections.singletonList(selectorData)));
        return SoulResultMessage.SUCCESS;
    }

    private List<DivideUpstream> buildDivideUpstreamList(final List<String> uriList) {
        return uriList.stream().map(uri -> DivideUpstream.builder()
                .upstreamHost("localhost")
                .protocol("http://")
                .upstreamUrl(uri)
                .weight(50)
                .build()).collect(Collectors.toList());
    }
}
