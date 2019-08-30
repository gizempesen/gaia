package io.codeka.gaia.stacks.controller;

import io.codeka.gaia.modules.repository.TerraformModuleRepository;
import io.codeka.gaia.runner.StackRunner;
import io.codeka.gaia.stacks.bo.Job;
import io.codeka.gaia.stacks.bo.StepType;
import io.codeka.gaia.stacks.repository.JobRepository;
import io.codeka.gaia.stacks.repository.StackRepository;
import io.codeka.gaia.stacks.workflow.JobWorkflow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class JobRestController {

    private JobRepository jobRepository;
    private StackRepository stackRepository;
    private TerraformModuleRepository moduleRepository;
    private StackRunner stackRunner;

    @Autowired
    public JobRestController(JobRepository jobRepository, StackRepository stackRepository,
                             TerraformModuleRepository moduleRepository, StackRunner stackRunner) {
        this.jobRepository = jobRepository;
        this.stackRepository = stackRepository;
        this.moduleRepository = moduleRepository;
        this.stackRunner = stackRunner;
    }

    @GetMapping(params = "stackId")
    public List<Job> jobs(@RequestParam String stackId){
        return this.jobRepository.findAllByStackId(stackId);
    }

    @GetMapping("/{id}")
    public Job job(@PathVariable String id){
        return this.jobRepository.findById(id).orElseThrow(JobNotFoundException::new);
    }

    @PostMapping("/{id}/{stepType}")
    public void planOrApplyJob(@PathVariable String id, @PathVariable StepType stepType) {
        var job = this.jobRepository.findById(id).orElseThrow(JobNotFoundException::new);
        var stack = this.stackRepository.findById(job.getStackId()).orElseThrow();
        var module = this.moduleRepository.findById(stack.getModuleId()).orElseThrow();

        if (StepType.PLAN == stepType) {
            this.stackRunner.plan(new JobWorkflow(job), module, stack);
        } else {
            this.stackRunner.apply(new JobWorkflow(job), module, stack);
        }
    }

}

@ResponseStatus(HttpStatus.NOT_FOUND)
class JobNotFoundException extends RuntimeException{
}
