package io.codeka.gaia.runner;

import io.codeka.gaia.modules.bo.TerraformModule;
import io.codeka.gaia.stacks.bo.Job;
import io.codeka.gaia.stacks.bo.JobType;
import io.codeka.gaia.stacks.bo.Stack;
import io.codeka.gaia.stacks.bo.StackState;
import io.codeka.gaia.stacks.repository.JobRepository;
import io.codeka.gaia.stacks.repository.StackRepository;
import io.codeka.gaia.stacks.repository.StepRepository;
import io.codeka.gaia.stacks.workflow.JobWorkflow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Runs a module instance
 */
@Service
public class StackRunner {

    private DockerRunner dockerRunner;
    private StackCommandBuilder stackCommandBuilder;
    private StackRepository stackRepository;
    private JobRepository jobRepository;
    private StepRepository stepRepository;

    private Map<String, Job> jobs = new HashMap<>();

    @Autowired
    public StackRunner(DockerRunner dockerRunner, StackCommandBuilder stackCommandBuilder,
                       StackRepository stackRepository, JobRepository jobRepository, StepRepository stepRepository) {
        this.dockerRunner = dockerRunner;
        this.stackCommandBuilder = stackCommandBuilder;
        this.stackRepository = stackRepository;
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
    }

    private String managePlanScript(JobType jobType, Stack stack, TerraformModule module) {
        if (jobType == JobType.RUN) {
            return stackCommandBuilder.buildPlanScript(stack, module);
        }
        return stackCommandBuilder.buildPlanDestroyScript(stack, module);
    }

    private void managePlanResult(Integer result, JobWorkflow jobWorkflow, Stack stack) {
        if (result == 0) {
            // diff is empty
            jobWorkflow.end();
        } else if (result == 2) {
            // there is a diff, set the status of the stack to : "TO_UPDATE"
            if (StackState.NEW != stack.getState() && jobWorkflow.getJob().getType() != JobType.DESTROY) {
                stack.setState(StackState.TO_UPDATE);
                stackRepository.save(stack);
            }
            jobWorkflow.end();
        } else {
            // error
            jobWorkflow.fail();
        }
    }

    private String manageApplyScript(JobType jobType, Stack stack, TerraformModule module) {
        if (jobType == JobType.RUN) {
            return stackCommandBuilder.buildApplyScript(stack, module);
        }
        return stackCommandBuilder.buildDestroyScript(stack, module);
    }

    private void manageApplyResult(Integer result, JobWorkflow jobWorkflow, Stack stack) {
        if (result == 0) {
            jobWorkflow.end();

            // update stack information
            stack.setState(jobWorkflow.getJob().getType() == JobType.RUN ? StackState.RUNNING : StackState.STOPPED);
            stackRepository.save(stack);
        } else {
            jobWorkflow.fail();
        }
    }

    /**
     * @param jobWorkflow
     * @param jobActionFn function applying o the job
     * @param scriptFn    function allowing to get the right script to execute
     * @param resultFn    function treating the result ot the executed script
     */
    private void treatJob(JobWorkflow jobWorkflow, Consumer<JobWorkflow> jobActionFn,
                          Supplier<String> scriptFn, IntConsumer resultFn) {
        // execute the workflow of the job
        jobActionFn.accept(jobWorkflow);

        var job = jobWorkflow.getJob();
        this.jobs.put(job.getId(), job);
        stepRepository.saveAll(job.getSteps());
        jobRepository.save(job);

        // get the wanted script
        var script = scriptFn.get();

        var result = this.dockerRunner.runContainerForJob(jobWorkflow, script);

        // manage the result of the execution of the script
        resultFn.accept(result);

        // save job to database
        stepRepository.saveAll(job.getSteps());
        jobRepository.save(job);
        this.jobs.remove(job.getId());
    }

    @Async
    public void plan(JobWorkflow jobWorkflow, TerraformModule module, Stack stack) {
        treatJob(
                jobWorkflow,
                JobWorkflow::plan,
                () -> managePlanScript(jobWorkflow.getJob().getType(), stack, module),
                result -> managePlanResult(result, jobWorkflow, stack)
        );
    }

    @Async
    public void apply(JobWorkflow jobWorkflow, TerraformModule module, Stack stack) {
        treatJob(
                jobWorkflow,
                JobWorkflow::apply,
                () -> manageApplyScript(jobWorkflow.getJob().getType(), stack, module),
                result -> manageApplyResult(result, jobWorkflow, stack)
        );
    }

    public Job getJob(String jobId) {
        if (this.jobs.containsKey(jobId)) {
            // try in memory
            return this.jobs.get(jobId);
        }
        // or find in repository
        return this.jobRepository.findById(jobId).orElseThrow(() -> new RuntimeException("job not found"));
    }

}
