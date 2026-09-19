package com.terrascout.orchestrator.app.repository;

import com.terrascout.orchestrator.core.domain.CommandExecution;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 命令执行审计仓储（表 command_execution：每次命令执行写记录）。
 */
public interface CommandExecutionRepository extends JpaRepository<CommandExecution, String> {
}
