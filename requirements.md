# Transflux - Microflow Orchestration Library Requirements

## 1. Overview

Transflux is a lightweight microflow orchestration library designed to automate the coordination of state changes for business entities. The library focuses on the logic and execution of transitions themselves - handling dependencies, sequencing, error handling, and compensations during state changes - rather than just defining states or managing long-term processes.

### 1.1 Problem Statement

Many business entities can be modeled as finite-state machines, with different states attached to different lifecycle stages. However, most applications implement these state machines from scratch using plain Java code and custom abstractions, leading to:
- Unique low-level code that reduces reliability and scalability
- Difficulty in understanding, extending, and maintaining services
- Lack of standardization across different entity types

### 1.2 Solution Goals

Transflux aims to provide a standard framework for finite-state machine entities and associated transition workflows that is:
- Lightweight and non-imposing, easily integrating with existing codebases
- Embedded and fully local to the application instance
- Built on existing persistence models without requiring its own storage
- Capable of orchestrating complex transitions with error compensation (similar to the Saga pattern)
- Supportive of reusable components (steps, triggers, listeners)
- Comes with DSL support for both declarative and programmatic definitions

## 2. Architecture

### 2.1 Core Components

#### 2.1.1 Component Identification

All components in Transflux (states, transitions, operations, steps, conditions, triggers, and others) must have unique identifiers for proper referencing and management.

**Component ID:**
- **Required property** for all components
- Must be unique within the component type (e.g., all state IDs must be unique within a state machine, all operation IDs must be unique within their scope)
- Used for internal referencing, component lookup, and programmatic access
- Should follow naming conventions suitable for programmatic use (e.g., kebab-case, camelCase)

**Component Name:**
- **Optional property** for components
- Provides human-readable description or display name
- Used for documentation, user interfaces, and logging
- Can contain spaces, special characters, and be more descriptive than IDs

**Example:**
```yaml
states:
  - id: trial-state
    name: "Trial Subscription State"
    
  - id: active-state
    name: "Active Subscription State"
```

There is one exception to this rule: to reduce boilerplate, conditions are not required to specify an id; if it's missing in the definition, the condition will be automatically assigned a unique identifier based on its class name or expression contents and the path from the root of the state machine definition to the condition.

#### 2.1.2 StateMachine
The central orchestrator that manages entity state transitions and coordinates all framework operations.

**Responsibilities:**
- Maintain the state transition matrix definition
- Validate transition requests against defined rules
- Execute transition operations and manage their lifecycle
- Handle trigger evaluation and activation
- Coordinate pre/post-conditions and listeners
- Manage operation contexts and data flow

**Key Interfaces:**
- Entity state management and transition execution
- Trigger registration and event processing
- Operation and step execution coordination
- Context and data mapping management

#### 2.1.3 State
Represents individual states in the state machine with associated metadata and behavior.

States are characterized by their transition patterns rather than explicit types:
- **Initial states** have no incoming transitions and serve as entry points where entities begin their lifecycle
- **Terminal states** have no outgoing transitions and represent final states in the entity lifecycle
- All other states can have both incoming and outgoing transitions

The state machine API provides functionality to force execution to start at any arbitrary state, enabling testing scenarios, debugging workflows, and recovery from abnormal situations.

**Forced State Execution:**
The API includes methods to bypass normal state transition rules and directly place entities into specific states. This capability supports:
- **Testing**: Initialize entities in specific states for unit and integration tests
- **Debugging**: Reproduce issues by placing entities in problematic states
- **Recovery**: Restore entities to valid states after system failures or data corruption

**Properties:**
- State identifier and metadata
- Valid outgoing transitions

#### 2.1.4 Transition
Defines valid state changes and their associated operations, conditions, and triggers.

**Components:**
- Source and target states
- Associated operation (optional)
- Pre-conditions (conditions that must be met **before** the transition is executed)
- Post-conditions (conditions that must be met **after** the transition is executed)
- Triggers (manual, event-based, data-based)
- Transition-specific listeners
- Compensation strategies

#### 2.1.5 Operation
Encapsulates the business logic executed during state transitions.

**Types:**
- **SimpleOperation**: Single-step operations with direct Java implementation
- **CompositeOperation**: Multi-step operations with declarative flow control

**Features:**
- Input/output type safety with context mapping
- Synchronous and asynchronous execution parts
- Error handling and compensation strategies
- Step-level granular control

#### 2.1.6 Step
Individual executable units within operations.

**Characteristics:**
- Context-aware execution
- Individual compensation strategies
- Reusable across different operations

#### 2.1.7 Context
Manages shared state during transition execution.

**Responsibilities:**
- Shared data storage during operation execution
- Type-safe data access and manipulation

#### 2.1.8 Trigger System
Manages various mechanisms for initiating state transitions.

**Trigger Types:**
- **ManualTrigger**: Explicit programmatic transition requests
- **EventTrigger**: Transitions based on external events
- **DataTrigger**: Transitions triggered when an entity instance is evaluated and matches defined conditions (class-based or expression-based)

#### 2.1.9 Condition System
Provides validation and gating mechanisms for transitions.

**Types:**
- **PreCondition**: Validates transition eligibility before execution
- **PostCondition**: Validates successful transition completion (transition will be rolled back, any compensation actions included, if post-condition is not met when the transition completes)

#### 2.1.10 Listener System
Enables observation and reaction to state machine events.

**Listener Types:**
- Transition start/completion listeners (before and after transition)

#### 2.1.11 Compensation Engine
Manages error recovery and rollback operations.

**Features:**
- Stack-based compensation execution (LIFO)
- Exception-specific compensation strategies

#### 2.1.12 State Resolver
Determines the current state of an entity to enable appropriate transition selection and validation.

**Purpose:**
To select the appropriate transition, the state machine needs to understand the current entity state. The state resolver provides a flexible mechanism to extract or compute the current state from an entity instance.

**Resolution Approaches:**
- **Dedicated Java Class**: Custom resolver class implementing StateResolver interface
- **Lambda Function**: Inline function for programmatic state resolution (Java API only)
- **SpEL Expression**: Expression-based state resolution using Spring Expression Language

**Key Characteristics:**
- State resolution is a property of the state machine itself, not individual transitions or triggers
- Resolvers are evaluated before transition eligibility checks
- Support for complex state derivation logic including computed states
- Type-safe resolution with compile-time validation in Java API

**Note:** it is expected that in the vast majority of cases, the state resolver will simply extract a specific state field from the entity instance, and that internal (model) entity states will map 1:1 to external (state machine) states.

### 2.2 Component Relationships

```
StateMachine
├── State Resolver (Class, Lambda, SpEL)
├── States (Initial, Terminal, Regular)
│   ├── Transitions
│   │   ├── Operations (Simple, Composite)
│   │   │   ├── Steps
│   │   │   ├── Context
│   │   │   └── Compensation Strategies
│   │   ├── Conditions (Pre/Post)
│   │   ├── Triggers (Manual, Event, Data)
│   │   └── Listeners
├── Trigger System
├── Compensation Engine
└── Metrics & Observability
```

### 2.3 Execution Flow

1. **Transition Request**: Initiated by triggers or manual calls
2. **State Resolution**: Determine current entity state using configured state resolver
3. **Pre-condition Evaluation**: Validate transition eligibility
4. **Listener Notification**: Notify registered transition start listeners
5. **Operation Execution**: Execute associated business logic
    - Sequential step execution
    - Compensation registration
    - Asynchronous part scheduling (if applicable)
6. **Post-condition Evaluation**: Validate successful completion
7. **Listener Notification**: Notify registered transition end listeners

### 2.4 Error Handling and Compensation

- **Exception Propagation**: Controlled exception handling with compensation triggers
- **Compensation Stack**: LIFO execution of registered compensation actions

## 3. YAML-based DSL Specification

The YAML-based DSL provides a declarative approach to defining state machines, transitions, and operations. It supports modular definitions with imports and references for reusability.

### 3.1 Reusable Component Libraries

To eliminate duplication and promote reusability, Transflux supports shared component libraries that can be defined once and referenced multiple times across different state machines, operations, and transitions.

#### 3.1.1 Component Library Structure

```yaml
# components/shared-components.yml
apiVersion: transflux/v1
# Top-level metadata belongs to the component library, not individual components
metadata:
  name: "Subscription Management Components"
  description: "Shared components for subscription management"

spec:
  # Shared Steps
  steps:
    - id: prepare-notifications
      name: "Prepare Notifications"
      class: com.example.steps.PrepareNotificationsStep
      description: "Prepare notification messages"
      
    - id: send-notifications
      class: com.example.steps.SendNotificationsStep
      description: "Send prepared notifications"
      
    - id: update-analytics
      name: "Update Analytics"
      class: com.example.steps.UpdateAnalyticsStep
      description: "Update analytics data"
      
    - id: activate-features
      class: com.example.steps.ActivateFeaturesStep
      description: "Activate related subscription features"
      
    - id: prepare-event-actor
      class: com.example.steps.PrepareEventActorStep
      description: "Prepare event actor for transition"
      
    - id: validate-prerequisites
      name: "Validate Prerequisites"
      class: com.example.steps.ValidatePrerequisitesStep
      description: "Validate operation prerequisites"
      compensation: com.example.compensations.ValidationCompensation
      
  # Shared Conditions
  conditions:
    - id: payment-method-valid
      name: "Payment Method Valid"
      class: com.example.conditions.PaymentMethodValidCondition
      description: "Verify payment method is valid"
        
    - id: subscription-features-activated
      class: com.example.conditions.FeaturesActivatedCondition
      description: "Verify all subscription features are activated"
      
    - id: business-hours
      name: "Business Hours Check"
      expression: "T(java.time.LocalTime).now().hour >= 9 && T(java.time.LocalTime).now().hour < 17"
      description: "Check if current time is within business hours"
      
  # Shared Triggers
  triggers:
    - id: payment-method-validated-event
      name: "Payment Method Validated"
      type: event
      event: PAYMENT_METHOD_VALIDATED
      filter:
        expression: "event.validation == CONFIRMED"
        
    - id: data-priority-change
      type: data
      condition:
        expression: "entity.status == 'READY_FOR_ACTIVATION' && entity.priority > 5"
        
  # Shared Listeners
  listeners:
    - id: audit-start
      name: "Audit Start Listener"
      class: com.example.listeners.TransitionStartListener
      description: "Audit transition start"
      
    - id: audit-complete
      class: com.example.listeners.TransitionCompleteListener
      description: "Audit transition completion"
      
    - id: subscription-activated
      name: "Subscription Activated Listener"
      class: com.example.listeners.SubscriptionActivatedListener
      description: "Handle subscription activation events"
      config:
        async: true
        
  # Shared Operations
  operations:
    - id: notification-flow
      name: "Notification Flow"
      type: composite
      description: "Standard notification flow"
      steps:
        - id: prepare-notifications-step
          step: prepare-notifications
        - id: send-notifications-step
          step: send-notifications
          
    - id: analytics-update
      type: simple
      step: update-analytics
      description: "Standard analytics update"
      async:
        enabled: true
```

#### 3.1.2 Component References

Components from libraries can be referenced directly by their unique name. All component IDs must be unique across all imported definitions. The component type is determined by the context where the reference is used:

```yaml
# Reference format: <component-name>

# Using component references in operations
operations:
  - id: activation-operation
    type: composite
    steps:
      - id: prepare-actor
        step: prepare-event-actor
        
      - id: validate
        step: validate-prerequisites
        
      - id: notifications
        operation: notification-flow
        
      - id: analytics
        operation: analytics-update

# Using component references in transitions
transitions:
  - id: draft-to-active
    from: DRAFT
    to: ACTIVE
    
    preConditions:
      - checkout-fulfilled
      - business-hours
      
    postConditions:
      - subscription-features-activated
      
    triggers:
      - checkout-event
      - data-priority-change
      
    listeners:
      onStart:
        - audit-start
      onComplete:
        - audit-complete
```

#### 3.1.3 Library Imports

```yaml
# Main state machine file with library imports
apiVersion: transflux/v1

# Import component libraries
# All component IDs must be unique across all imported definitions
imports:
- path: components/shared-components.yml
- path: components/subscription-specific-components.yml
- path: operations/subscription-operations.yml

stateMachine:
  id: subscription-state-machine
  name: "Subscription State Machine"
  entityType: com.example.Subscription
  
  # Use imported components directly by their unique IDs
  transitions:
    - id: trial-to-active
      from: TRIAL
      to: ACTIVE
      operation: activate-subscription
      
      preConditions:
        - payment-method-valid
        - subscription-specific-validation
        
      triggers:
        - end-of-trial-cron
```

### 3.2 State Machine Definition

#### 3.2.1 Basic Structure

Note: only a single state machine definition is allowed per file.

```yaml
# subscription-state-machine.yml
apiVersion: transflux/v1

imports:
- operations/subscription-operations.yml
- triggers/subscription-triggers.yml
- conditions/subscription-conditions.yml

stateMachine:
  id: subscription-state-machine
  name: "Subscription State Machine"
  version: 1.0.0
  description: "State machine for subscription lifecycle management"
  
  entityType: com.example.Subscription
  
  # State resolver configuration
  stateResolver:
    # Option 1: Using a dedicated Java class
    class: com.example.resolvers.SubscriptionStateResolver
    
    # Option 2: Using SpEL expression (alternative to class)
    # expression: "entity.status"
  
  states:
    - id: TRIAL
      name: "Trial State"
      description: "Initial trial state"
      
    - id: ACTIVE
      description: "Active subscription state"
      
    - id: SUSPENDED
      description: "Suspended subscription state"
      
    - id: CANCELLED
      description: "Cancelled subscription state"
      
    - id: EXPIRED
      description: "Expired subscription state"

  transitions:
    - id: trial-to-active
      name: "Trial to Active Transition"
      from: TRIAL
      to: ACTIVE
      # Imported, pre-configured operation reference
      operation: activate-subscription
      preConditions:
        - payment-method-valid
      triggers:
        - id: end-of-trial-cron
          type: manual
      
    - id: active-to-suspended
      from: ACTIVE
      to: SUSPENDED
      # In-line operation definition (example)
      operation:
        id: evaluate-suspension
        type: composite
        description: "Evaluate suspension handling with retry or notify branches"
        steps:
          - id: evaluate-risk
          - id: branch-on-risk
            type: conditional
            branches:
              - id: low-risk-retry
                condition:
                  expression: "@riskService.isLowRisk(entity.id)"
                steps:
                  - id: schedule-retry
                    class: com.example.steps.ScheduleRetryPaymentStep
              - id: default-notify
                steps:
                  - id: notify-user
                    class: com.example.steps.NotifyPaymentIssueStep
      triggers:
        - id: payment-failed
          type: data
          condition:
            predicate: com.example.triggers.PaymentFailedTrigger
      
    - id: suspended-to-cancelled
      from: SUSPENDED
      to: CANCELLED
      operation:
        id: cancel-subscription
        type: simple
        description: "Cancel subscription with compensation"
        class: com.example.operations.CancelSubscriptionOperation
        compensation: com.example.compensations.RefundPartialFeesCompensation
      preConditions:
        - no-recovery-7-days
      triggers:
        - id: cancellation-cron
          type: manual
      
    - id: active-to-expired
      from: ACTIVE
      to: EXPIRED
```

#### 3.2.2 State Configuration

```yaml
states:
  - id: ACTIVE
    name: "Active State"
    description: "Active subscription state"
```

### 3.3 Transition Configuration

#### 3.3.1 Basic Transition

```yaml
transitions:
  - id: trial-to-active
    name: "Trial to Active Transition"
    from: TRIAL
    to: ACTIVE
    description: "Activate trial subscription"
    
    operation: activate-subscription
    
    preConditions:
      - payment-method-valid
        
    postConditions:
      - milestones-activated
    
    triggers:
      - id: end-of-trial-cron # manual trigger example (e.g., external cron job)
        type: manual
        
      - id: external-activation
        type: data
        class: com.example.triggers.SubscriptionActivatedTrigger
        
    listeners:
      onStart:
        - audit-start
      onComplete:
        - audit-complete
```

#### 3.3.2 Advanced Triggers

```yaml
triggers:
  # Manual trigger (default)
  - type: manual
    id: manual-trigger
    
  # Event-based trigger
  - id: payment-method-validated-event
    type: event
    event: PAYMENT_METHOD_VALIDATED
      
  # Data-based trigger with dedicated class
  - id: data-trigger
    type: data
    condition:
      predicate: com.example.triggers.SubscriptionDataTrigger
    description: "Custom trigger class that evaluates subscription instance"
      
  # Data-based trigger with expression-based condition
  - id: data-priority-change-trigger
    type: data
    condition:
      expression: "entity.status == 'READY_FOR_ACTIVATION' && entity.priority > 5"
    description: "Expression-based entity evaluation"
```

### 3.4 Operation Definitions

#### 3.4.1 Simple Operation

```yaml
# operations/subscription-operations.yml
apiVersion: transflux/v1
operations:
  - id: activate-subscription
    name: "Activate Subscription"
    description: "Activate a trial subscription and prepare for notifications"
    type: simple
    class: com.example.operations.ActivateSubscriptionOperation
    
    context:
      class: com.example.contexts.ActivationContext
```

#### 3.4.2 Composite Operation

```yaml
operations:
  - id: complex-activation
    type: composite
    description: "Complex activation with multiple steps"
    
    context:
      class: com.example.contexts.ComplexActivationContext
      
    steps:
      - id: prepare-event-actor
        type: step
      - id: validate-prerequisites
        # Step is the default, so it can be omitted
      - id: lock-resources
        # Can define steps in-line
        class: com.example.operations.LockResourcesOperation
        description: "Lock resources for activation"
        
      - id: multi-branch-conditional
        type: conditional
        branches:
          - id: high-priority-branch
            condition: 
              predicate: com.example.predicates.HighPriorityPredicate
            steps:
              - id: high-priority-processing
                class: com.example.steps.HighPriorityStep
              - id: urgent-notification
                class: com.example.steps.UrgentNotificationStep
                
          - id: medium-priority-branch
            condition: 
              expression: "entity.priority >= 5 && entity.priority < 8"
            steps:
              - id: medium-priority-processing
                class: com.example.steps.MediumPriorityStep
                
          - id: vip-customer-branch
            condition: 
              predicate: com.example.predicates.VipCustomerPredicate
            steps:
              - id: vip-processing
                class: com.example.steps.VipProcessingStep
              - id: account-manager-notification
                class: com.example.steps.AccountManagerNotificationStep
                
        default:
          steps:
            - id: standard-processing
              class: com.example.steps.StandardProcessingStep
            - id: standard-notification
              class: com.example.steps.StandardNotificationStep
              
      - id: finalize
        class: com.example.actions.FinalizeActivationStep
        
    errorHandling:
      - exception: com.example.exceptions.RecoverableException
        condition:
          predicate: com.example.predicates.RecoverableErrorPredicate
        compensation: com.example.compensations.RecoverableCompensation
        
      - exception: java.lang.Exception
        compensation: com.example.compensations.GeneralCompensation
        
    async:
      enabled: true
      startBeforeStep: finalize
      # or startAfterStep: <step-id>
      steps:
        - id: async-notifications
          class: com.example.steps.AsyncNotificationStep
          
        - id: external-integrations
          class: com.example.steps.ExternalIntegrationStep
```

#### 3.4.3 Multi-Branch Conditional Operations

Multi-branch conditional operations allow for complex decision-making with multiple predicates and a default fallback branch. Multi-branch conditionals evaluate multiple conditions in sequence and execute the first matching branch, or the default branch if no conditions match.

```yaml
operations:
  - id: priority-based-routing
    type: composite
    description: "Route processing based on multiple priority conditions"
    
    steps:
      - id: multi-priority-routing
        type: conditional
        branches:
          # High priority branch - checked first
          - id: critical-priority
            condition:
              predicate: com.example.predicates.CriticalPriorityPredicate
            steps:
              - id: escalate-immediately
                class: com.example.steps.EscalateStep
              - id: notify-management
                class: com.example.steps.ManagementNotificationStep
              - id: expedited-processing
                class: com.example.steps.ExpeditedProcessingStep
                
          # Medium-high priority branch
          - id: high-priority
            condition:
              expression: "entity.priority >= 8 && entity.customerTier == 'PREMIUM'"
            steps:
              - id: priority-processing
                class: com.example.steps.PriorityProcessingStep
              - id: premium-notification
                class: com.example.steps.PremiumNotificationStep
                
          # VIP customer branch (regardless of priority)
          - id: vip-customer
            condition:
              predicate: com.example.predicates.VipCustomerPredicate
            steps:
              - id: vip-processing
                class: com.example.steps.VipProcessingStep
              - id: account-manager-alert
                class: com.example.steps.AccountManagerAlertStep
                
          # Time-sensitive branch
          - id: time-sensitive
            condition:
              expression: "entity.deadline.isBefore(T(java.time.LocalDate).now().plusDays(1))"
            steps:
              - id: urgent-processing
                class: com.example.steps.UrgentProcessingStep
                
          # Business hours branch
          - id: business-hours
            condition:
              expression: "T(java.time.LocalTime).now().hour >= 9 && T(java.time.LocalTime).now().hour < 17"
            steps:
              - id: business-hours-processing
                class: com.example.steps.BusinessHoursProcessingStep
              - id: same-day-notification
                class: com.example.steps.SameDayNotificationStep
                
        # Default branch - executed if no conditions match
        default:
          steps:
            - id: standard-processing
              class: com.example.steps.StandardProcessingStep
            - id: standard-notification
              class: com.example.steps.StandardNotificationStep
            - id: queue-for-batch
              class: com.example.steps.QueueForBatchStep
```

**Execution Semantics:**
1. Branches are evaluated in the order they are defined
2. The first branch whose condition evaluates to `true` is executed
3. Once a branch is executed, no further conditions are evaluated
4. If no branch conditions match, the `default` branch is executed
5. If no `default` branch is defined and no conditions match, either the warning is logged and the step is skipped, or an error is raised, based on configuration

**Advanced Multi-Branch Example with Nested Operations:**

```yaml
operations:
  - id: complex-approval-workflow
    type: composite
    description: "Complex approval workflow with multiple decision points"
    
    steps:
      - id: approval-routing
        type: conditional
        branches:
          # Auto-approval branch
          - id: auto-approve
            condition:
              predicate: com.example.predicates.AutoApprovalEligiblePredicate
            steps:
              - id: auto-approve-action
                class: com.example.steps.AutoApproveStep
              - id: log-auto-approval
                class: com.example.steps.LogAutoApprovalStep
                
          # Manager approval branch
          - id: manager-approval
            condition:
              expression: "entity.amount <= 10000 && entity.riskScore < 5"
            steps:
              - id: request-manager-approval
                class: com.example.steps.RequestManagerApprovalStep
              - id: nested-approval-workflow
                operation: manager-approval-workflow
                
          # Committee approval branch
          - id: committee-approval
            condition:
              expression: "entity.amount > 10000 || entity.riskScore >= 5"
            steps:
              - id: prepare-committee-review
                class: com.example.steps.PrepareCommitteeReviewStep
              - id: schedule-committee-meeting
                class: com.example.steps.ScheduleCommitteeMeetingStep
              - id: committee-workflow
                operation: committee-approval-workflow
                
        default:
          steps:
            - id: manual-review-required
              class: com.example.steps.ManualReviewRequiredStep
            - id: assign-to-specialist
              class: com.example.steps.AssignToSpecialistStep
```

### 3.5 Context and Data Mapping

#### 3.5.1 Context Definition

```yaml
contexts:
  - id: activation-context
    class: com.example.contexts.ActivationContext
```

#### 3.5.2 Context Usage Examples

Applications must populate the context before execution and read results after completion:

```java
// Application populates context before execution
ActivationContext context = new ActivationContext();
context.setSubscriptionId(entity.getId());
context.setPaymentMethodId(entity.getPaymentMethodId());
context.setActivatedBy("SYSTEM");

// Execute transition with context
stateMachine.executeTransition("trial-to-active", entity, context);

// Application reads results from context after execution
log.info("Activated subscription {} at {} with result {}", entity.getId(), context.getActivatedAt(), context.getActivationResult());
```

### 3.6 Conditions and Validators

#### 3.6.1 Pre/Post Conditions

```yaml
conditions:
  - id: checkout-fulfilled
    class: com.example.conditions.CheckoutFulfilledCondition
      
  - id: milestones-activated
    class: com.example.conditions.MilestonesActivatedCondition
    
  - id: business-hours
    expression: "T(java.time.LocalTime).now().isAfter(T(java.time.LocalTime).of(9, 0)) and T(java.time.LocalTime).now().isBefore(T(java.time.LocalTime).of(17, 0))"
```

### 3.7 Listeners and Hooks

```yaml
listeners:
  # Transition listeners (before and after transition)
  transitionListeners:
    - transition: draft-to-active
      onStart:
        - class: com.example.listeners.ActivationStartListener
        
    - transition: "*"  # Global transition listener
      onComplete:
        - class: com.example.listeners.TransitionAuditListener
```

### 3.8 Global Configuration

```yaml
config:
  # Async settings
  async:
    threadPoolSize: 10
    queueCapacity: 100
    
  # Metrics settings
  metrics:
    enabled: true
    flowLabel: subscription-management
    
  # Logging settings
  logging:
    level: INFO
    includeContext: true
    includeTimings: true
```

### 3.9 Expression Language Support

Transflux uses SpEL (Spring Expression Language) for defining conditions, filters, and simple computations with powerful expression evaluation. 

SpEL provides powerful expression capabilities with or without DI integration. Here are examples of how to use SpEL in various contexts:

```yaml
# conditions/spel-conditions.yml
apiVersion: transflux/v1
kind: Conditions
conditions:
  # Simple field access
  - name: status-ready
    expression: "entity.status == 'READY'"
    
  # Complex condition with method calls
  - name: checkout-fulfilled
    expression: "@checkoutService.isCheckoutFulfilled(entity.checkoutUid)"
    
  # Date/time conditions
  - name: business-hours
    expression: |
      T(java.time.LocalTime).now().isAfter(T(java.time.LocalTime).of(9, 0)) && 
      T(java.time.LocalTime).now().isBefore(T(java.time.LocalTime).of(17, 0))
      
  # Collection operations
  - name: all-milestones-active
    expression: "entity.milestones.![state].contains('INACTIVE') == false"
    
  # Conditional logic
  - name: priority-based-validation
    expression: |
      entity.priority > 8 ? 
        @validationService.strictValidation(entity) : 
        @validationService.basicValidation(entity)

# Data-based triggers with SpEL expression evaluation
triggers:
  - type: data
    condition:
      expression: "entity.status == 'PENDING' && entity.priority > 5"

  - type: data
    condition:
      # Expedite if status is PENDING and the request is marked as expedited in context
      expression: "entity.status == 'PENDING' && (context?.expedited ?: false)"
```

## 4. Java-based Builder DSL Specification

The Java-based builder DSL provides a programmatic, type-safe approach to defining state machines, transitions, and operations. It emphasizes fluent interfaces, compile-time safety, and IDE support.

### 4.1 Reusable Component Registry

To eliminate duplication and promote reusability, the Java DSL supports a component registry system that allows defining components once and referencing them multiple times across different state machines, operations, and transitions.

#### 4.1.1 Component Registry Structure

```java
// Define a component registry
@Component
public class SubscriptionComponentRegistry implements ComponentRegistry {
    
    // Shared Actions
    @RegisterAction("prepare-notifications")
    public PrepareNotificationsAction prepareNotificationsAction() {
        return new PrepareNotificationsAction();
    }
    
    @RegisterAction("send-notifications")
    public SendNotificationsAction sendNotificationsAction() {
        return new SendNotificationsAction();
    }
    
    @RegisterAction("update-analytics")
    public UpdateAnalyticsAction updateAnalyticsAction() {
        return new UpdateAnalyticsAction();
    }
    
    @RegisterAction("activate-milestones")
    public ActivateMilestonesAction activateMilestonesAction() {
        return new ActivateMilestonesAction();
    }
    
    @RegisterAction("prepare-event-actor")
    public PrepareEventActorAction prepareEventActorAction() {
        return new PrepareEventActorAction();
    }
    
    @RegisterAction("validate-prerequisites")
    public ValidatePrerequisitesAction validatePrerequisitesAction() {
        return new ValidatePrerequisitesAction();
    }
    
    // Shared Conditions
    @RegisterCondition("payment-method-valid")
    public PaymentMethodValidCondition paymentMethodValidCondition() {
        return new PaymentMethodValidCondition();
    }
    
    @RegisterCondition("milestones-activated")
    public MilestonesActivatedCondition milestonesActivatedCondition() {
        return new MilestonesActivatedCondition();
    }
    
    @RegisterCondition("business-hours")
    public BusinessHoursCondition businessHoursCondition() {
        return new BusinessHoursCondition();
    }
    
    // Shared Triggers
    @RegisterTrigger("payment-method-validated-event")
    public EventTrigger paymentMethodValidatedEvent() {
        return EventTrigger.builder()
            .event(Event.PAYMENT_METHOD_VALIDATED)
            .filterBy("subscriptionId", entity -> ((Subscription) entity).getId())
            .build();
    }
    
    @RegisterTrigger("data-priority-change")
    public DataTrigger dataPriorityChangeTrigger() {
        return DataTrigger.builder()
            .evaluateEntity(entity -> {
                Subscription subscription = (Subscription) entity;
                return "READY_FOR_ACTIVATION".equals(subscription.getStatus()) && 
                       subscription.getPriority() > 5;
            })
            .build();
    }
    
    // Shared Listeners
    @RegisterListener("audit-start")
    public TransitionStartListener auditStartListener() {
        return new TransitionStartListener();
    }
    
    @RegisterListener("audit-complete")
    public TransitionCompleteListener auditCompleteListener() {
        return new TransitionCompleteListener();
    }
    
    @RegisterListener("subscription-activated")
    public SubscriptionActivatedListener subscriptionActivatedListener() {
        return new SubscriptionActivatedListener();
    }
    
    // Shared Operations
    @RegisterOperation("notification-flow")
    public CompositeOperation notificationFlowOperation() {
        return compositeOperation("notification-flow")
            .step("prepare-notifications", "prepare-notifications")
            .step("send-notifications", "send-notifications")
            .build();
    }
    
    @RegisterOperation("analytics-update")
    public SimpleOperation analyticsUpdateOperation() {
        return simpleOperation("analytics-update")
            .action("update-analytics")
            .async(true)
            .build();
    }
}
```

#### 4.1.2 Component References

Components from the registry can be referenced directly by their unique name. All component IDs must be unique across all registered components:

```java
// Using component references in operations
CompositeOperation activationOperation = compositeOperation("activation-operation")
    .step("prepare-actor", "prepare-event-actor")
    .step("validate", "validate-prerequisites")
    .step("notifications", "notification-flow")
    .step("analytics", "analytics-update")
    .build();

// Using component references in transitions
draftActiveTransition
    .addPreCondition("checkout-fulfilled")
    .addPreCondition("business-hours")
    .addPostCondition("milestones-activated")
    .addTrigger("checkout-event")
    .addTrigger("data-priority-change")
    .onStart("audit-start")
    .onComplete("audit-complete");

```

#### 4.1.3 Registry Configuration and Injection

```java
// Configuration class
@Configuration
@EnableTransflux
public class TransfluxConfig {
    
    @Bean
    public ComponentRegistry subscriptionComponentRegistry() {
        return new SubscriptionComponentRegistry();
    }
    
    @Bean
    public StateMachine<Subscription> subscriptionStateMachine(ComponentRegistry registry) {
        return Transflux.defineStateMachine()
            .forEntityType(Subscription.class)
            .withComponentRegistry(registry)
            // ... state machine definition using component references
            .build();
    }
}

// Alternative programmatic registration
ComponentRegistry registry = ComponentRegistry.builder()
    .registerAction("prepare-notifications", PrepareNotificationsAction.class)
    .registerAction("send-notifications", SendNotificationsAction.class)
    .registerAction("update-analytics", UpdateAnalyticsAction.class)
    .registerCondition("payment-method-valid", PaymentMethodValidCondition.class)
    .registerCondition("milestones-activated", MilestonesActivatedCondition.class)
    .registerListener("audit-start", TransitionStartListener.class)
    .registerListener("audit-complete", TransitionCompleteListener.class)
    .build();

StateMachine<Subscription> stateMachine = Transflux.defineStateMachine()
    .forEntityType(Subscription.class)
    .withComponentRegistry(registry)
    .build();
```


### 4.2 Core API Structure

#### 4.2.1 StateMachine Definition

```java
// Basic state machine definition
StateMachine<Subscription> subscriptionStateMachine = Transflux.defineStateMachine()
    .forEntityType(Subscription.class)
    .withName("subscription-state-machine")
    .withVersion("1.0.0")
    
    // State resolver configuration - Option 1: Dedicated class
    .withStateResolver(SubscriptionStateResolver.class)
    
    // State resolver configuration - Option 2: Lambda function
    // .withStateResolver(entity -> entity.getStatus())
    
    // State resolver configuration - Option 3: SpEL expression
    // .withStateResolver("entity.status")
    
    // Define states
    .state(TRIAL)
        .withDescription("Initial trial state")
        .transitionsTo(ACTIVE)
        .end()
        
    .state(ACTIVE)
        .withDescription("Active subscription state")
        .transitionsTo(SUSPENDED, EXPIRED)
        .end()
        
    .state(SUSPENDED)
        .withDescription("Suspended subscription state")
        .transitionsTo(CANCELLED)
        .end()
        
    .state(CANCELLED, EXPIRED)
        .end()
        
    .build();

// Alternative compact syntax
StateMachine<Subscription> compactStateMachine = Transflux.defineStateMachine()
    .forEntityType(Subscription.class)
    .states(
        state(TRIAL).transitionsTo(ACTIVE),
        state(ACTIVE).transitionsTo(SUSPENDED, EXPIRED),
        state(SUSPENDED).transitionsTo(CANCELLED),
        state(CANCELLED, EXPIRED)
    )
    .build();
```

#### 4.2.2 Advanced State Configuration

```java
StateMachine<Subscription> stateMachine = Transflux.defineStateMachine()
    .forEntityType(Subscription.class)
    
    .state(ACTIVE)
        .withDescription("Active subscription state")
        .withMetadata("displayName", "Active")
        
        // State listeners
        .onEntry(OfferActivatedListener.class)
        .onEntry(NotificationListener.class, config -> config
            .property("template", "offer-activated")
            .property("async", true))
        .onExit(OfferDeactivatedListener.class)
        
        .transitionsTo(DECLINED, WITHDRAWN, EXPIRED, ACCEPTED)
        .end()
        
    .build();
```

### 4.3 Transition Configuration

```java
// Get transition reference
Transition<Offer, ActivationContext> draftActiveTransition = 
    stateMachine.getTransition(DRAFT, ACTIVE);

// Configure transition
draftActiveTransition
    .withName("draft-to-active")
    .withDescription("Activate draft offer")
    
    // Set operation
    .setOperation(ActivateOperation.class)
    .usingContext(ActivationContext.class)
        .mappingInput(input -> input
            .map("entity.id").to("offerId")
            .map("entity.checkoutUid").to("checkoutId")
            .mapConstant("SYSTEM").to("activatedBy"))
        .mappingOutput(output -> output
            .map("activatedAt").to("entity.activatedTimestamp")
            .map("activationResult.status").to("entity.activationStatus"))
        .end()
        
    // Pre/post conditions
    .addPreCondition(CheckoutFulfilledCondition.class)
    .addPreCondition("milestones-ready", this::milestonesReady)
    .addPostCondition(MilestonesActivatedCondition.class)
    
    // Triggers
    .addManualTrigger()
    .addEventTrigger(Event.CHECKOUT_FULFILLED)
    .addDataTrigger(OfferActivatedTrigger.class)
    
    // Listeners
    .onStart(TransitionStartListener.class)
    .onComplete(TransitionCompleteListener.class)
    .onError(TransitionErrorListener.class);
```

### 4.4 Operation Definitions

#### 4.4.1 Simple Operation

```java
// Define operation class
public class ActivateOperation implements Operation<Offer, ActivationContext> {
    
    @Inject
    private CheckoutService checkoutService;
    
    @Inject
    private MilestonesService milestonesService;
    
    @Override
    public void execute(Offer offer, ActivationContext context, Transition<Offer, ActivationContext> transition) {
        // Business logic using offer and context data
        Long offerId = offer.getId();
        validateOffer(offerId);
        
        // Execute steps via transition
        transition.step("prepare-event-actor", PrepareEventActorAction.class);
        transition.step("validate-prerequisites", ValidatePrerequisitesAction.class);
        
        // Set results in context
        context.setActivatedAt(Instant.now());
        context.setActivationStatus(ActivationStatus.SUCCESS);
    }
    
    @Override
    public Class<CompensationAction<Offer, ActivationContext>> getCompensation() {
        return ActivationCompensation.class;
    }
    
    // Alternatively, return the compensation directly
    @Override
    public CompensationAction<Offer, ActivationContext> getCompensation(Offer offer, ActivationContext context) {
        return new ActivationCompensation();
    }
}

// Configure operation on transition
draftActiveTransition
    .setOperation(ActivateOperation.class)
    .usingContext(ActivationContext.class)
    .withCompensation(ActivationCompensation.class)
    .withAsync(async -> async
        .enabled(true)
        .steps("notifyExternalSystems", "updateAnalytics"))
    .end();
```

#### 4.4.2 Composite Operation (Declarative Style)

```java
draftActiveTransition.setOperation(
    compositeOperation("complex-activation")
        .withDescription("Complex activation with multiple steps")
        .usingContext(ComplexActivationContext.class)
        
        // Sequential steps
        .step("prepare-event-actor", PrepareEventActorAction.class)
        
        .step("validate-prerequisites", ValidatePrerequisitesAction.class)
            .withCompensation(ValidationCompensation.class)
            
        
        // Multi-branch conditional
        .conditional("priority-routing")
            .branch("high-priority")
                .condition(HighPriorityPredicate.class)
                .step("high-priority-processing", HighPriorityAction.class)
                .step("urgent-notification", UrgentNotificationAction.class)
            .end()
            
            .branch("medium-priority")
                .condition(entity -> entity.getPriority() >= 5 && entity.getPriority() < 8)
                .step("medium-priority-processing", MediumPriorityAction.class)
            .end()
            
            .branch("vip-customer")
                .condition(VipCustomerPredicate.class)
                .step("vip-processing", VipProcessingAction.class)
                .step("account-manager-notification", AccountManagerNotificationAction.class)
            .end()
            
            .defaultBranch()
                .step("standard-processing", StandardProcessingAction.class)
                .step("standard-notification", StandardNotificationAction.class)
            .end()
        .end()
        
        // Multi-branch conditional with comprehensive examples
        .conditional("priority-routing")
            .branch("critical-priority")
                .condition(CriticalPriorityPredicate.class)
                .step("escalate-immediately", EscalateAction.class)
                .step("notify-management", ManagementNotificationAction.class)
                .step("expedited-processing", ExpeditedProcessingAction.class)
            .end()
            
            .branch("high-priority")
                .condition(entity -> entity.getPriority() >= 8 && 
                          "PREMIUM".equals(entity.getCustomerTier()))
                .step("priority-processing", PriorityProcessingAction.class)
                .step("premium-notification", PremiumNotificationAction.class)
            .end()
            
            .branch("vip-customer")
                .condition(VipCustomerPredicate.class)
                .step("vip-processing", VipProcessingAction.class)
                .step("account-manager-alert", AccountManagerAlertAction.class)
            .end()
            
            .branch("time-sensitive")
                .condition(entity -> entity.getDeadline().isBefore(
                    LocalDateTime.now().plusDays(1)))
                .step("urgent-processing", UrgentProcessingAction.class)
            .end()
            
            .defaultBranch()
                .step("standard-processing", StandardProcessingAction.class)
                .step("standard-notification", StandardNotificationAction.class)
                .step("queue-for-batch", QueueForBatchAction.class)
            .end()
        .end()
        
        
        .step("finalize", FinalizeActivationAction.class)
        
        // Error handling
        .onException(RecoverableException.class)
            .matching(e -> e.getCode() == RECOVERABLE_ERROR)
            .compensateWith(RecoverableCompensation.class)
        .onAllExceptions()
            .compensateWith(GeneralCompensation.class)
        
        // Async part
        .async()
            .step("async-notifications", AsyncNotificationStep.class)
            .step("external-integrations", ExternalIntegrationStep.class)
        .end()
        
    .end()
);
```

### 4.5 Context and Data Mapping

#### 4.5.1 Context Definition

```java
// Define context class
@TransfluxContext
public class ActivationContext {
    
    @Required
    private Long offerId;
    
    private String checkoutId;
    private Instant activatedAt;
    private EventActor eventActor;
    
    // Getters and setters
    // ...
    
    @Validate
    public void validateContext() {
        if (offerId == null || offerId <= 0) {
            throw new InvalidContextException("Invalid offer ID");
        }
    }
}

// Context builder
ActivationContext context = ActivationContext.builder()
    .offerId(offer.getId())
    .checkoutId(offer.getCheckoutUid())
    .build();
```

#### 4.5.2 Context Usage in Transitions

Applications are responsible for populating context before execution and reading results after completion:

```java
// Define transition with context
draftActiveTransition
    .setOperation(ActivateOperation.class)
    .usingContext(ActivationContext.class)
    .end();

// Application usage example
public void activateOffer(Offer entity) {
    // Application populates context before execution
    ActivationContext context = new ActivationContext();
    context.setOfferId(entity.getId());
    context.setCheckoutId(entity.getCheckoutUid());
    context.setActivatedBy("SYSTEM");
    
    // Execute transition with context
    stateMachine.executeTransition("draft-to-active", entity, context);
    
    // Application reads results from context after execution
    entity.setActivatedTimestamp(context.getActivatedAt());
    entity.setActivationStatus(context.getActivationResult().getStatus());
}
```

#### 4.5.3 Nested Operations Context Handling

For nested operations, there are two approaches:

**Option 1: Using Parent Context**
```java
// Nested operation uses the same context as parent
public class ParentOperation implements SimpleOperation<Offer, ActivationContext> {
    
    @Override
    public void execute(Offer offer, ActivationContext context, Transition<Offer, ActivationContext> transition) {
        // Parent operation logic
        context.setParentData("some value");
        
        // Nested operation uses same context and transition
        nestedOperation.execute(offer, context, transition);
        
        // Parent can access nested operation results
        String result = context.getNestedResult();
    }
}

public class NestedOperation implements SimpleOperation<Offer, ActivationContext> {
    
    @Override
    public void execute(Offer offer, ActivationContext context, Transition<Offer, ActivationContext> transition) {
        // Access parent data
        String parentData = context.getParentData();
        
        // Set nested results
        context.setNestedResult("processed: " + parentData);
    }
}
```

**Option 2: Explicit Context Mapping**
```java
// Nested operation with its own context and explicit mapping
public class ParentWithMappingOperation implements SimpleOperation<Offer, ActivationContext> {
    
    @Override
    public void execute(Offer offer, ActivationContext parentContext, Transition<Offer, ActivationContext> transition) {
        // Create nested context and map data from parent
        NestedContext nestedContext = new NestedContext();
        nestedContext.setInputData(parentContext.getOfferId());
        nestedContext.setConfiguration(parentContext.getConfiguration());
        
        // Execute nested operation
        nestedOperation.execute(offer, nestedContext, transition);
        
        // Map results back to parent context
        parentContext.setNestedProcessingResult(nestedContext.getOutputData());
        parentContext.setNestedTimestamp(nestedContext.getCompletedAt());
    }
}

// Declarative workflow with optional context mapping
public CompositeOperation<Offer, ActivationContext> createAdvancedWorkflow() {
    return compositeOperation("advanced-activation-workflow")
        .withDescription("Advanced activation with nested operations and context mapping")
        .usingContext(ActivationContext.class)
        
        // Regular steps
        .step("prepare-event-actor", PrepareEventActorAction.class)
        .step("validate-prerequisites", ValidatePrerequisitesAction.class)
        
        // Nested operation without mapping (uses parent context)
        .operation("simple-nested", SimpleNestedOperation.class)
        
        // Nested operation with class-based mapping
        .operation("payment-processing", PaymentProcessingOperation.class)
            .withContextMapping(PaymentContextMapper.class)
        
        // Nested operation with lambda-based mapping
        .operation("risk-assessment", RiskAssessmentOperation.class)
            .withContextMapping(
                // Input mapping lambda
                parent -> {
                    RiskContext riskContext = new RiskContext();
                    riskContext.setOfferId(parent.getOfferId());
                    riskContext.setCustomerId(parent.getCustomerId());
                    riskContext.setAmount(parent.getOfferAmount());
                    return riskContext;
                },
                // Output mapping lambda
                (parent, risk) -> {
                    parent.setRiskScore(risk.getCalculatedScore());
                    parent.setRiskLevel(risk.getRiskLevel());
                    parent.setRiskFactors(risk.getIdentifiedFactors());
                }
            )
            
        
        .step("finalize-activation", FinalizeActivationAction.class)
        
    .end();
}

// ContextMapper interface definition
public interface ContextMapper<P, N> {
    N mapInput(P parentContext);
    void mapOutput(P parentContext, N nestedContext);
}

// Class-based context mapper example
public class PaymentContextMapper implements ContextMapper<ActivationContext, PaymentContext> {
    
    @Override
    public PaymentContext mapInput(ActivationContext parent) {
        PaymentContext paymentContext = new PaymentContext();
        paymentContext.setOfferId(parent.getOfferId());
        paymentContext.setPaymentMethodId(parent.getPaymentMethodId());
        paymentContext.setAmount(parent.getOfferAmount());
        paymentContext.setCurrency(parent.getCurrency());
        return paymentContext;
    }
    
    @Override
    public void mapOutput(ActivationContext parent, PaymentContext payment) {
        parent.setPaymentTransactionId(payment.getTransactionId());
        parent.setPaymentStatus(payment.getStatus());
        parent.setPaymentTimestamp(payment.getProcessedAt());
    }
}
```

### 4.6 Steps

#### 4.6.1 Step Definition

```java
public class PrepareEventActorStep implements Step<Offer, ActivationContext> {
    
    @Inject
    private EventActorService eventActorService;
    
    @Override
    public void execute(Offer offer, ActivationContext context, Transition<Offer, ActivationContext> transition) {
        EventActor eventActor = eventActorService.createEventActor(
            context.getOfferId(), "SYSTEM");
        context.setEventActor(eventActor);
    }
    
    @Override
    public CompensationStep<ActivationContext> getCompensation() {
        return context -> eventActorService.removeEventActor(
            context.getEventActor().getId());
    }
}

public class ValidatePrerequisitesStep 
    implements Step<Offer, ActivationContext> {
    
    @Override
    public void execute(Offer offer, ActivationContext context, Transition<Offer, ActivationContext> transition) {
        // Validation logic using context data
        boolean isValid = performValidation(context.getOfferId(), context.getCheckoutId());
        
        // Set validation results in context
        context.setValidationResult(isValid);
        context.setValidatedAt(Instant.now());
    }
}
```

#### 4.6.2 Step Configuration

```java
// Step with compensation
compositeOperation("complex-operation")
    .step("validate-prerequisites", ValidatePrerequisitesStep.class)
        .withCompensation(ValidationCompensation.class)
        
    // Step with custom error handling
    .step("risky-step", RiskyStep.class)
        .onException(SpecificException.class)
            .compensateWith(SpecificCompensation.class)
        .onException(Exception.class)
            .compensateWith(GeneralCompensation.class)
    .end();
```

### 4.7 Conditions and Validators

#### 4.7.1 Condition Definition

```java
@Component
public class CheckoutFulfilledCondition implements Condition<Offer> {
    
    @Inject
    private CheckoutService checkoutService;
    
    @Override
    public boolean evaluate(Offer offer) {
        return checkoutService.isCheckoutFulfilled(offer.getCheckoutUid());
    }
}

@Component
public class MilestonesActivatedCondition implements Condition<Offer> {
    
    @Inject
    private MilestonesService milestonesService;
    
    @Override
    public boolean evaluate(Offer offer) {
        return milestonesService.getMilestones(offer.getId())
            .stream()
            .allMatch(m -> m.getState() == MilestoneState.ACTIVE);
    }
}

// Lambda-based conditions
draftActiveTransition
    .addPreCondition("checkout-fulfilled", 
        offer -> checkoutService.isCheckoutFulfilled(offer.getCheckoutUid()))
    .addPostCondition("milestones-activated",
        offer -> milestonesService.getMilestones(offer.getId())
            .stream().allMatch(m -> m.getState() == ACTIVE));
```

#### 4.7.2 Advanced Condition Configuration

```java
draftActiveTransition
    // Condition configuration
    .addPreCondition(CheckoutFulfilledCondition.class)
    
    // Condition with custom error message
    .addPreCondition("business-hours", this::isBusinessHours, condition -> condition
        .withErrorMessage("Transitions only allowed during business hours")
        .withErrorCode("BUSINESS_HOURS_VIOLATION"))
    
    // Synchronous condition evaluation
    .addPreCondition(ValidationCondition.class);
```

### 4.8 Listeners and Hooks

#### 4.8.1 Listener Definition

```java
@Component
public class TransitionAuditListener implements TransitionListener<Offer, ?> {
    
    @Inject
    private AuditService auditService;
    
    @Override
    public void onTransition(Offer offer, Transition<Offer, ?> transition, 
                                 Object context) {
        if (transition.isStarted()) {
            auditService.logTransitionStart(offer, transition);
        } else {
            auditService.logTransitionComplete(offer, transition);
        }
    }
}
```

#### 4.8.2 Listener Registration

```java
// Transition listeners (before and after transition)
draftActiveTransition
    .onStart(ActivationStartListener.class)
    .onComplete(ActivationCompleteListener.class);

// Global transition listeners
stateMachine
    .onAnyTransitionStart(TransitionAuditListener.class)
    .onAnyTransitionComplete(TransitionAuditListener.class);
```

### 4.9 Execution and Usage

#### 4.9.1 Manual Transition Execution

```java
// Basic transition execution
TransitionResult<Offer> result = stateMachine
    .entity(offer)
    .transitionTo(ACTIVE);

// Transition with context
TransitionResult<Offer> result = stateMachine
    .entity(offer)
    .withContext(context -> context
        .property("source", "API")
        .property("userId", currentUser.getId()))
    .transitionTo(ACTIVE);

// Selecting specific transition
TransitionResult<Offer> result = stateMachine
    .entity(offer)
    .transitionTo(ACTIVE, "draft-to-active");

```

#### 4.9.2 Event and Trigger Processing

```java
// Process event
stateMachine
    .entity(offer)
    .processEvent(Event.CHECKOUT_FULFILLED, eventData);

// Process data change
stateMachine
    .entity(offer)
    .processDataChange();
```

#### 4.9.3 Batch Operations

```java
// Batch transition
List<TransitionResult<Offer>> results = stateMachine
    .entities(offers)
    .withContext(offer -> new ActivationContext(offer.getId()))
    .transitionTo(ACTIVE);

```

### 4.10 Configuration and Integration

#### 4.10.1 Framework Configuration

```java
// Basic configuration
TransfluxConfiguration config = TransfluxConfiguration.builder()
    .asyncThreadPoolSize(10)
    .asyncQueueCapacity(100)
    .metricsEnabled(true)
    .flowLabel("offer-management")
    .build();

Transflux transflux = Transflux.create(config);
```

#### 4.10.2 Spring Integration

```java
@Configuration
@EnableTransflux
public class TransfluxConfig {
    
    @Bean
    public TransfluxConfiguration transfluxConfiguration() {
        return TransfluxConfiguration.builder()
            .metricsEnabled(true)
            .build();
    }
    
    @Bean
    public StateMachine<Offer> offerStateMachine() {
        return Transflux.defineStateMachine()
            .forEntityType(Offer.class)
            // ... state machine definition
            .build();
    }
}

// Usage in service
@Service
public class OfferService {
    
    @Inject
    private StateMachine<Offer> offerStateMachine;
    
    public void activateOffer(Offer offer, ActivationInput input) {
        TransitionResult<Offer> result = offerStateMachine
            .entity(offer)
            .input(input)
            .transitionTo(ACTIVE);
            
        if (!result.isSuccess()) {
            throw new ActivationException(result.getError());
        }
    }
}
```

#### 4.10.3 Metrics and Observability

```java
// Custom metrics
@Component
public class CustomMetricsCollector implements MetricsCollector {
    
    @Override
    public void recordTransitionStart(String stateMachine, String transition) {
        // Custom metrics logic
    }
    
    @Override
    public void recordTransitionComplete(String stateMachine, String transition, 
                                       Duration duration) {
        // Custom metrics logic
    }
}

// Configuration
TransfluxConfiguration config = TransfluxConfiguration.builder()
    .metricsCollector(CustomMetricsCollector.class)
    .flowLabel("offer-management")
    .build();
```

## 5. Non-Functional Requirements

### 5.1 Performance
- Minimal overhead for simple transitions
- Optimized trigger evaluation and matching

### 5.2 Observability
- Comprehensive metrics (success/failure counts, timing histograms)
- Configurable logging with predictable logger names
- Custom flow labels for metric separation
- Detailed operation and step-level instrumentation

### 5.3 Maintainability
- Clear separation of concerns
- Reusable component design
- Comprehensive documentation and examples
- Consistent API patterns

## 6. Integration Requirements

### 6.1 Dependency Injection
- Framework-agnostic component registration
- Support for Spring, CDI, Guice
- Manual component wiring capabilities

### 6.2 Class Instance Factory System
- **Component Instantiation Framework**
    - Generic component factory interface with type safety
    - Constructor parameter resolution and dependency injection
    - Named component registration and retrieval mechanisms
    - Singleton vs prototype instance management strategies
    - Circular dependency detection and prevention

- **Multi-Framework Integration**
    - Integration with Spring ApplicationContext for Spring-based applications
    - Integration with Google Guice Injector for Guice-based applications
    - Integration with CDI BeanManager for CDI-based applications
    - Fallback to reflection-based instantiation when no DI framework is available
    - Custom factory function registration for specialized component creation

- **YAML DSL Integration**
    - Component instantiation from YAML class definitions
    - Constructor parameter injection from YAML configuration
    - Named component registration from YAML component libraries
    - Factory-based component resolution during YAML parsing
    - YAML factory configuration section support

### 6.3 Enhanced Testing Framework
- **State Machine Testing Support**
    - Comprehensive test wrapper for state machine instances
    - Transition path recording and tracking capabilities
    - Context snapshot capture at key transition points
    - Step-level execution tracking for granular testing
    - Timing information collection for performance testing
    - Test data builders for entities and contexts

- **AssertJ-Inspired Assertion Framework**
    - Fluent assertion API similar to Camunda's test assertions
    - State assertions (current state, transition history verification)
    - Transition assertions (execution outcomes, path validation)
    - Context assertions (data verification, transformation validation)
    - Operation assertions (execution results, compensation verification)
    - Custom assertion extensions for domain-specific validations

- **Transition Path Recording**
    - Ordered path tracking for all executed transitions
    - Detailed execution metadata capture (timestamps, durations, outcomes)
    - Context state snapshots at each transition point
    - Error and compensation action recording
    - Configurable recording granularity (full detail vs summary)

- **Testing Integration Requirements**
    - Framework-agnostic DI container testing support
    - Component registration and lifecycle testing capabilities
    - Mock DI container implementations for isolated testing
    - DI framework detection and fallback testing
    - Performance benchmarking and regression testing support
    - Integration with popular testing frameworks (Spock, JUnit, TestNG)

**Example Usage:**
```java
// State machine testing with path recording
TestStateMachine<Offer> testStateMachine = TestStateMachine.wrap(offerStateMachine);

// Execute transition with recording
TransitionResult<Offer> result = testStateMachine
    .entity(offer)
    .input(activationInput)
    .transitionTo(ACTIVE);

// Assert using fluent API
TransfluxAssertions.assertThat(testStateMachine)
    .hasExecutedTransitionPath("DRAFT", "ACTIVE")
    .hasExecutedSteps("validate-prerequisites", "activate-milestones", "send-notifications")
    .hasNoCompensationActions();

TransfluxAssertions.assertThat(result)
    .isSuccessful()
    .hasTargetState(ACTIVE)
    .hasContextValue("activatedAt", notNullValue())
    .hasContextValue("notificationsSent", true);
```

## 7. IDE Plugin Requirements

### 7.1 Overview

To enhance developer productivity and provide a seamless development experience with Transflux, dedicated IDE plugins are required for JetBrains IDEs (IntelliJ IDEA, WebStorm, etc.) and VSCode-based IDEs. These plugins will provide comprehensive support for YAML-based workflow definitions, cross-language navigation, and visual workflow management.

### 7.2 Target IDEs

#### 7.2.1 JetBrains IDEs
- **IntelliJ IDEA** (Ultimate and Community editions)
- **WebStorm** and other JetBrains IDEs with YAML support
- Plugin distributed via JetBrains Marketplace

#### 7.2.2 VSCode-based IDEs
- **Visual Studio Code**
- **VSCodium** and other VSCode-compatible editors
- Extension distributed via Visual Studio Marketplace and Open VSX Registry

### 7.3 Core Features

#### 7.3.1 Enhanced YAML Syntax Highlighting

**Advanced Syntax Support:**
- **Schema-aware highlighting**: Context-sensitive highlighting based on Transflux YAML schemas
- **Semantic highlighting**: Different colors for states, transitions, operations, steps, and triggers
- **Error highlighting**: Real-time validation with inline error indicators
- **Nested structure visualization**: Indentation guides and bracket matching for complex workflows

**Transflux-specific Elements:**
- **State definitions**: Highlighting for state types (starting, intermediate, terminal)
- **Transition configurations**: Visual distinction for transition properties and conditions
- **Operation declarations**: Highlighting for operation types and their configurations
- **Step definitions**: Clear visualization of step sequences and dependencies
- **Context mappings**: Highlighting for input/output mappings and data flow
- **Trigger configurations**: Visual distinction for different trigger types

#### 7.3.2 Cross-Language Navigation

**YAML to Java Navigation:**
- **Step class references**: Navigate from YAML step definitions to corresponding Java classes
- **Operation implementations**: Jump from YAML operation declarations to Java operation classes
- **Context class references**: Navigate to Java context classes from YAML configurations
- **Entity class references**: Jump to Java entity classes from state machine definitions
- **Trigger implementations**: Navigate to Java trigger classes from YAML trigger configurations

**Java to YAML Navigation:**
- **Find usages in YAML**: Show all YAML files that reference a Java class
- **Step usage tracking**: Find all workflows that use a specific step implementation
- **Operation usage analysis**: Locate all state machines using a particular operation
- **Context usage mapping**: Find YAML files that reference specific context classes

**Bidirectional References:**
- **Reference highlighting**: Highlight related elements when cursor is positioned on references
- **Quick definition preview**: Show Java class definitions in popup when hovering over YAML references
- **Breadcrumb navigation**: Show navigation path between related YAML and Java elements

#### 7.3.3 Workflow Visualization

**State Machine Diagrams:**
- **Interactive state diagrams**: Visual representation of states and transitions
- **Transition flow visualization**: Arrows showing possible state transitions with labels
- **State type indicators**: Visual distinction for initial, regular, and terminal states
- **Conditional transition paths**: Visual representation of conditional branches and decision points

**Operation Flow Diagrams:**
- **Step sequence visualization**: Flowchart representation of operation steps
- **Conditional branching**: Decision diamonds and alternative paths
- **Compensation flow**: Visual representation of compensation strategies

**Interactive Features:**
- **Click-to-navigate**: Click on diagram elements to navigate to corresponding code
- **Zoom and pan**: Navigate large workflows with zoom controls
- **Minimap overview**: Bird's-eye view of complex workflows
- **Export capabilities**: Export diagrams as PNG, SVG, or PDF

#### 7.3.4 Intelligent Code Assistance

**Auto-completion:**
- **Schema-based completion**: Context-aware suggestions based on Transflux schemas
- **Class name completion**: Auto-complete Java class names in YAML references
- **Property completion**: Suggest valid properties for each configuration section
- **Value completion**: Suggest valid values for enumerated properties

**Code Generation:**
- **YAML template generation**: Generate YAML templates for common workflow patterns
- **Java class scaffolding**: Generate Java step/operation classes from YAML definitions
- **Context class generation**: Create context classes based on YAML input/output mappings
- **Test class generation**: Generate test classes for workflow components

**Refactoring Support:**
- **Rename refactoring**: Rename Java classes and update all YAML references
- **Move class refactoring**: Update YAML references when Java classes are moved
- **Extract operation**: Extract inline operations to separate YAML files
- **Inline operation**: Inline external operation references

#### 7.3.5 Validation and Error Detection

**Real-time Validation:**
- **Schema validation**: Validate YAML against Transflux schemas
- **Reference validation**: Verify that Java class references exist and are accessible
- **Type compatibility**: Check input/output type compatibility between steps
- **Circular dependency detection**: Detect and warn about circular references

**Error Reporting:**
- **Inline error markers**: Show errors directly in the editor with descriptive messages
- **Error panel integration**: List all validation errors in IDE error panels
- **Quick fixes**: Provide automated fixes for common validation errors
- **Severity levels**: Distinguish between errors, warnings, and informational messages

### 7.4 Advanced Features

#### 7.4.1 Debugging Support

**Workflow Debugging:**
- **Breakpoint support**: Set breakpoints in YAML workflow definitions
- **Step-by-step execution**: Debug workflow execution step by step
- **Variable inspection**: Inspect context variables and step inputs/outputs
- **Call stack visualization**: Show workflow execution stack

**Integration with Java Debugging:**
- **Seamless debugging**: Debug from YAML into Java code and back
- **Context variable mapping**: Map YAML context variables to Java objects
- **Execution flow tracking**: Track execution flow between YAML and Java components

#### 7.4.2 Testing Integration

**Test Generation:**
- **Unit test scaffolding**: Generate unit tests for workflow components
- **Integration test templates**: Create integration test templates for complete workflows
- **Mock generation**: Generate mock objects for external dependencies

**Test Execution:**
- **Run configurations**: Create run configurations for workflow tests
- **Test result visualization**: Show test results with workflow context
- **Coverage reporting**: Show test coverage for workflow components

#### 7.4.3 Documentation Integration

**Inline Documentation:**
- **Hover documentation**: Show documentation for workflow elements on hover
- **Quick documentation**: Display comprehensive documentation in popup windows
- **Schema documentation**: Show schema documentation for YAML properties

**Documentation Generation:**
- **Workflow documentation**: Generate documentation from YAML workflow definitions
- **API documentation**: Generate API documentation for Java components
- **Diagram export**: Export workflow diagrams for documentation

### 7.5 Configuration and Customization

#### 7.5.1 Plugin Configuration

**Schema Configuration:**
- **Custom schema support**: Support for custom Transflux schema extensions
- **Schema validation levels**: Configurable validation strictness
- **Schema update notifications**: Notify when schema updates are available

**Appearance Customization:**
- **Color scheme integration**: Integrate with IDE color schemes
- **Custom highlighting**: Allow customization of syntax highlighting colors
- **Diagram themes**: Multiple themes for workflow diagrams

#### 7.5.2 Project Integration

**Project Setup:**
- **Project templates**: Provide project templates with Transflux configuration
- **Build tool integration**: Integration with Maven/Gradle for schema validation
- **Dependency management**: Assist with Transflux dependency configuration

**Multi-module Support:**
- **Cross-module navigation**: Navigate between YAML and Java across modules
- **Module-aware validation**: Validate references across project modules
- **Shared component libraries**: Support for shared workflow component libraries

### 7.6 Performance and Scalability

#### 7.6.1 Performance Requirements

**Responsiveness:**
- **Fast syntax highlighting**: Sub-100ms highlighting for typical YAML files
- **Efficient validation**: Background validation without blocking UI
- **Incremental parsing**: Parse only changed portions of large files

**Memory Efficiency:**
- **Lazy loading**: Load workflow diagrams and complex visualizations on demand
- **Memory optimization**: Efficient memory usage for large workflow definitions
- **Caching strategies**: Cache parsed schemas and validation results

#### 7.6.2 Scalability

**Large Project Support:**
- **Scalable indexing**: Efficient indexing of large numbers of workflow files
- **Fast search**: Quick search across all workflow definitions
- **Batch operations**: Efficient batch processing for refactoring operations

### 7.7 Distribution and Maintenance

#### 7.7.1 Release Strategy

**Version Alignment:**
- **Transflux version compatibility**: Plugin versions aligned with Transflux library versions
- **Backward compatibility**: Support for multiple Transflux versions
- **Migration assistance**: Help users migrate between Transflux versions

**Update Mechanism:**
- **Automatic updates**: Automatic plugin updates through IDE update mechanisms
- **Schema updates**: Automatic schema updates when new Transflux versions are released
- **Feature announcements**: In-IDE notifications for new features

#### 7.7.2 Support and Documentation

**User Documentation:**
- **Installation guide**: Step-by-step installation instructions
- **Feature documentation**: Comprehensive documentation for all plugin features
- **Video tutorials**: Video tutorials for common workflows and advanced features

**Developer Resources:**
- **Plugin API documentation**: Documentation for extending plugin functionality
- **Contribution guidelines**: Guidelines for community contributions
- **Issue tracking**: Public issue tracking for bug reports and feature requests

## 8. Future Considerations

### 8.1 Pluggable Persistence Layer
- State machine definition storage
- Transition history auditing
- Entity state persistence and recovery

### 8.2 Distributed Operations
- Distributed task execution
- Cluster-wide locking mechanisms
- Failure handling in distributed environments

### 8.3 Long-running Operations
- Progress tracking and monitoring
- Timeout and retry mechanisms
- Distributed transaction support