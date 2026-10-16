package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paiagent.common.UserContext;
import com.paiagent.dto.WorkflowRequest;
import com.paiagent.dto.WorkflowResponse;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.WorkflowMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工作流服务。
 *
 * <p>迭代7（评审报告 A1）：所有读写按资源归属（owner）过滤——
 * 列表只返回「本人 + system」的工作流，按 id 的读写先校验归属。
 * 修复前无任何归属概念，任意登录用户可查看/修改/删除全部工作流。
 */
@Service
public class WorkflowService extends ServiceImpl<WorkflowMapper, Workflow> {
    
    /**
     * 创建工作流
     */
    public WorkflowResponse createWorkflow(WorkflowRequest request) {
        Workflow workflow = new Workflow();
        workflow.setName(request.getName());
        workflow.setDescription(request.getDescription());
        workflow.setFlowData(request.getFlowData());
        workflow.setEngineType(request.getEngineType());
        // 迭代7 A1：写入归属，资源从创建起即受 owner 隔离
        workflow.setOwner(UserContext.currentOwner());
        
        this.save(workflow);
        
        return toResponse(workflow);
    }
    
    /**
     * 更新工作流
     */
    public WorkflowResponse updateWorkflow(Long id, WorkflowRequest request) {
        Workflow workflow = requireOwned(id);
        
        workflow.setName(request.getName());
        workflow.setDescription(request.getDescription());
        workflow.setFlowData(request.getFlowData());
        workflow.setEngineType(request.getEngineType());
        
        this.updateById(workflow);
        
        return toResponse(workflow);
    }
    
    /**
     * 删除工作流
     */
    /**
     * 删除工作流。
     *
     * <p>迭代7 A1：删除前校验归属，防止跨用户删除。
     * <p>迭代7（报告中危项 12）：补事务边界，保证「归属校验 + 删除」原子完成。
     * 注：该工作流的执行历史（execution_record / snapshot / variable）属审计数据，
     * 按设计保留、不随工作流级联删除。
     */
    @Transactional
    public void deleteWorkflow(Long id) {
        requireOwned(id);
        this.removeById(id);
    }
    
    /**
     * 获取工作流详情
     */
    public WorkflowResponse getWorkflowById(Long id) {
        return toResponse(requireOwned(id));
    }
    
    /**
     * 查询工作流列表
     */
    public List<WorkflowResponse> listWorkflows() {
        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<>();
        // 迭代7 A1：只返回「本人 + system」的工作流
        wrapper.in(Workflow::getOwner, visibleOwners())
               .orderByDesc(Workflow::getUpdatedAt);
        
        List<Workflow> workflows = this.list(wrapper);
        return workflows.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }
    
    /**
     * 取「当前用户可见」的工作流实体（迭代7 A1）。
     * 执行入口（执行/续跑/SSE/最近执行）复用同一归属校验，
     * 避免出现「列表看不到、却能按 id 直接执行」的绕过路径。
     *
     * @throws IllegalArgumentException 工作流不存在或不属于当前用户
     */
    public Workflow getOwnedWorkflow(Long id) {
        return requireOwned(id);
    }
    
    /**
     * 按「id + 归属」取工作流（迭代7 A1）：不存在或不属于当前用户都视为不存在。
     * 有意不区分 404/403——避免通过错误信息探测他人资源是否存在。
     */
    private Workflow requireOwned(Long id) {
        Workflow workflow = this.getOne(new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getId, id)
                .in(Workflow::getOwner, visibleOwners()));
        if (workflow == null) {
            throw new IllegalArgumentException("工作流不存在或无权访问");
        }
        return workflow;
    }
    
    /**
     * 可见归属集合（迭代7 A1）：本人资源 + 系统资源。
     * 用户之间严格隔离；系统内部创建的共享资源对所有用户可见，
     * 与既有「自动创建默认资源」的行为保持兼容。
     */
    private List<String> visibleOwners() {
        String current = UserContext.currentOwner();
        return UserContext.SYSTEM_OWNER.equals(current)
                ? List.of(UserContext.SYSTEM_OWNER)
                : List.of(current, UserContext.SYSTEM_OWNER);
    }
    
    /**
     * 转换为响应 DTO
     */
    private WorkflowResponse toResponse(Workflow workflow) {
        WorkflowResponse response = new WorkflowResponse();
        BeanUtils.copyProperties(workflow, response);
        return response;
    }
}
