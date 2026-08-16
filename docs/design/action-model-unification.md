# Action Model Unification

> Design note for [Phase 3.8](../history/phase-3-triggers-listeners.md#38-action-model-unification). Written before the refactor; reconciled into `requirements.md` when it landed.

Transflux currently has two runtime concepts for "a unit of work that runs during a transition": `Step` and `Operation`. This note argues that the distinction has already collapsed in practice, describes the single concept that replaces it, and works through the parts that are not a mechanical rename - mostly the execution ordering, which turns out to have a real defect that the unification fixes.

Note that `requirements.md` has its own, entirely unrelated §3.8 (Global Configuration). Whenever this note says §3.8 it means the roadmap item, not the requirements section.

## Why

`Step.execute` and `Operation.execute` have identical signatures - `(entity, context, transition)`. Not similar, identical. Both receive the same per-execution `Transition` view, which means both can dispatch other components by id; §4.4.1 of `requirements.md` shows a simple operation doing exactly that. So containment is not the differentiator either.

The runtime has already unified them, quietly, over the previous phases:

* `BoundAction` is a sealed supertype of `BoundStep` and `BoundOperation`, and exists for no reason other than to let the composite executor iterate a heterogeneous list;
* `CompositeMember` wraps a `BoundAction`, not one kind or the other, so by the time execution starts the distinction is already erased;
* `TransitionResult.getExecutedPath()` records both kinds interleaved in a single list;
* `ActionRef` has six variants which are three structurally identical pairs (`ById` / `OperationById`, `InlineInstance` / `OperationInlineInstance`, `InlineClass` / `OperationInlineClass`), each pair differing only in which `Component` variant it expects to resolve against;
* `Component.Step` and `Component.Operation` are the same record modulo the payload type;
* `StateMachineDefImpl` keeps two registration maps and two guards (`checkIdNotRegisteredAsStep` / `checkIdNotRegisteredAsOperation`) whose entire job is to keep two kinds apart inside what is already a single id namespace;
* `Transition` declares 16 dispatch overloads which are eight pairs, identical in shape, differing only in the method name.

What is left of the split is policy, not capability. Compensation is step-only, because `Step` declares `getCompensation` and `Operation` does not. And only an operation may attach to a transition, which forces the workaround CLAUDE.md currently documents as idiomatic: "authoring a single-step composite is the way to use a step as a stand-alone operation target". That is a wrapper object existing solely to satisfy a type check.

BPMN, which is where the container framing came from in the first place, does not make this split: a Task and a SubProcess are both Activities, and a SubProcess attaches anywhere a Task does. Our version is more restrictive than the model that inspired it, and we are paying for the restriction in duplicated surface area.

Sequencing matters here. §3.5's Action Listeners are blocked on this, because they would otherwise have to design `ActionKind` and the global dispatch seam against a model that is about to change. Phase 4 fixes compensation semantics and Phase 5 writes the YAML schema; both would be writing against the dead model. This is the largest refactor left before 1.0 and it wants to happen now rather than after those.

## The model

There is one runtime type:

```java
@FunctionalInterface
public interface Action<T, C> {
    void execute(T entity, C context, Transition<T, C> transition);

    default Compensation<T, C> getCompensation(T entity, C context) {
        return null;
    }
}
```

Both `Step<T, C>` and `Operation<T, C>` are deleted. `Action` is the union of the two - the shared `execute` plus the compensation hook that used to be step-only.

There are two authoring forms, mutually exclusive:

* **declarative** - an ordered child list, where declaration order *is* execution order. There is no Java body; the framework synthesizes the executable that walks the list.
* **imperative** - a Java body, no bound children, free to dispatch ids declared elsewhere.

**Vocabulary: "step" is an imperative action, "operation" is a declarative one.** Applied consistently, this is what makes `SimpleOperationDef` disappear - a "simple operation" was always a step, we just could not attach a step to a transition. And `ConditionalStepDef` becomes a conditional *operation*: a declarative-container variant whose ordering rule is "first matching branch" rather than "all, in order". Leaving room for further variants later (a parallel container is the obvious candidate, though not in 1.0).

**"Action" is the runtime noun. The DSL splits its verbs by what the call site is doing**, not by what the callee is. At every position where an action can appear there are two categories:

* **reference** - name an action registered elsewhere and run it here. The site does not know, and must not care, which form the callee was authored in; that is a property of the callee's own registration.
* **declaration** - bring a new action into existence at this position, in the enclosing scope. Here the site does know the form, because it is the one choosing it.

Declaration cannot collapse to a single verb, and that is a language constraint rather than a matter of taste: the two configurer forms would be `action(String, Consumer<StepDef<T, C>>)` and `action(String, Consumer<OperationDef<T, C>>)`, which erase to the same signature and will not compile. `TransitionDef` carries exactly this pair today (`simpleOperation` and `compositeOperation`, both with configurers), so the collision is not hypothetical. Two declaration verbs are forced on us.

Reference, on the other hand, wants exactly one verb - and if it borrows either declaration verb, it starts asserting something it cannot know. So the two categories are split by part of speech:

* **references use `run(...)`** - one verb everywhere a reference can appear: a member of a declarative container, a member of a conditional branch, the action attached to a transition, and imperative dispatch from inside an action body. Eight overloads (bare id, registered mapper id, inline `Function`, inline `ContextMapper`, plus the `Identifiable` and mixed siblings), replacing the 16 that `Transition` declares today.
* **declarations use the nouns** - `step(...)` for an imperative action, `operation(...)` for a declarative one, `conditional(...)` for the first-matching-branch variant.

Verbs invoke, nouns declare, and you can tell the two apart without reading the arguments:

```java
t.operation("complex-activation", op -> op
    .step("prepare-actor", PrepareActorStep.class)
    .run("validate-payment-method")
    .run("charge-card", "payment-from-order")
    .conditional("tier-routing", c -> c
        .branch("premium", b -> b
            .condition(PremiumTier.class)
            .run("premium-processing"))))
```

| Site | Reference | Declaration |
|---|---|---|
| SM-level registration | - | `step(...)` / `operation(...)` |
| Transition | `run(id)` | `step(...)` / `operation(...)` |
| Container member | `run(id)` | `step(...)` / `conditional(...)` |
| Branch member | `run(id)` | `step(...)` |
| Inside an action body | `run(id)` | - |

`operation(...)` is absent from the container-member row on purpose: declaring a whole child container inline at a member position does not exist today and is not added here (see Fallout). A container references another container by id.

This also disposes of a diagnostic we currently need. `composite.step("charge")` asserts what the callee was authored as, and when the assertion is wrong you get "references id 'charge' which is registered as a step, not an operation". The call site was making a claim it had no business making; `run("charge")` makes no claim, and the message goes away along with the cross-kind guards behind it.

Note that a transition holds exactly one action rather than a list, so `t.run("activate")` reads as an instruction where the rest of the transition's configurer reads as configuration. That is a fair reading anyway ("when this transition fires, run activate"), and the exactly-one constraint is enforced by validation rather than by the verb - `simpleOperation` / `compositeOperation` do not signal it in their names today either.

`StepPath` becomes `ActionPath`, and the bound record carries an `ActionKind`.

**Any action attaches to a transition.** The single-step-composite workaround disappears, and so does the sentence in CLAUDE.md recommending it.

**The authored form is retained as metadata** on the bound record - `BoundAction(id, action, kind)` with `ActionKind` being `STEP` or `OPERATION`. Diagnostics keep saying "step 'charge'" and "operation 'activate'" rather than flattening everything to "action", and §3.5's `ActionExecution` payload reads its `ActionKind` off exactly this field. The enum constants are named after the vocabulary rather than after imperative/declarative, because that is what a user reading a stack trace or a listener filter will have written in their own DSL. The JavaDoc records the equivalence.

`ActionKind` is *metadata only*. It does not select a runtime path - see below.

## Runtime semantics

Today there are two runners, and they do different things in a different order.

A step, via `StateMachineImpl.runBoundStep`: capture the compensation, push it, execute, then record the id. It never touches the operation stack.

An operation, via `TransitionView.runChildOperation`: record the id, push onto the operation stack, execute, pop. It never captures a compensation.

These are not two views of one rule, they are two rules, and the difference is observable. Take a transition attaching a composite `activate`, which declares a member step `charge`, whose body calls `view.step("audit")`:

```
executedPath today:   activate, activate/audit, activate/charge
executedPath after:   activate, activate/charge, activate/charge/audit
```

Two things are wrong on the left. `audit` is qualified as a child of `activate` rather than of `charge`, because steps do not push the operation stack - so the reported tree is not the tree that ran. And `activate/audit` appears *before* `activate/charge`, i.e. a child is listed before the parent that produced it, which directly contradicts §2.1.4's "an entry for an operation appears immediately before any sub-entries it produces". The rule holds only for entries that happen to be operations.

There is a second, related inconsistency. If `charge` throws, its compensation was already pushed, so it runs and lands on `compensatedPath` - but `charge` never reached its `recordExecutedId` call, so it is *not* on `executedPath`. The result reports compensating something it also reports never having executed.

So the two runners collapse into one, and the uniform rule is:

1. capture the compensation (`getCompensation`, may return null);
2. record the id on `executedPath`;
3. push the id onto the operation stack;
4. execute;
5. pop the operation stack.

This is the operation rule for ordering and nesting, plus the step rule for compensation, applied to everything. Note that the transition's own root action goes through the same runner - today `executeTransitionInternal` hand-inlines steps 2 through 5 for it, which is one more copy of the logic to keep in sync and one more place the rules can drift apart.

Two behaviour changes follow, and both are intentional:

* a throwing action now appears on `executedPath` (it was invoked, after all, and its compensation is on the other list);
* an imperative action that dispatches children now qualifies them under itself, so `executedPath` reflects the actual call tree at every level rather than only at operation boundaries.

Specs that pin the old ordering need semantic revision, not renaming. `StateMachineImplCompensationSpec` and `TransitionResultSpec` are the two that encode it directly.

Important: `ActionKind` plays no part in any of the above. If it ever starts selecting a runtime path, we have quietly reintroduced the split under a new name.

## Compensation

Phase 4 owns the compensation engine - exception-specific routing, `onException` / `onAllExceptions`, per-branch async stacks. This phase deliberately ships no new compensation DSL. What it does is settle the model those things will be built on, and one capability falls out of the unification whether we want it or not.

That capability is the dynamic hook. Because there is one interface and it carries `getCompensation(entity, context)`, *every* action can now return a compensation - including one attached directly to a transition, which previously could not. This is not new DSL, it is the absence of an artificial restriction, and the uniform runner above already calls the hook for everything. A declarative container synthesized by the framework returns null from it, so nothing changes for existing definitions.

What Phase 4 still has to build, and what this note fixes as the intended semantics so Phase 4 does not have to re-decide them:

* **A static, def-side `withCompensation(...)` on both forms.** An imperative action can return its compensation dynamically from `getCompensation`; a declarative container has no Java object to hang that on and must declare one statically on its def. This asymmetry is not created by the unification - both shapes exist in the DSL today - the unification just stops hiding it behind the type split.
* **Container compensation is additive, not a replacement.** A container's own compensation and its children's all run. The container's does not supersede or cancel them. Since the children were pushed after the container (the container is pushed on entry, before it dispatches anything), LIFO unwinding drains the children first and the container last, which is the order you want and which the existing single stack already gives for free.
* **Push-on-entry means a container's compensation runs even when its first child fails immediately**, before the container itself did anything of its own. This is consistent with the existing "capture before execute" rule for steps, and it is deliberate - a container that allocated something in order to dispatch its children should get the chance to release it. But it reads like a bug in a stack trace unless it is written down, so: it is written down.

## Fallout

| Today | After | Note |
|---|---|---|
| `org.transflux.core.operation` | `org.transflux.core.action` | the package is named after its central abstraction, as `core.state` / `core.transition` / `core.condition` / `core.trigger` all are |
| `Step<T, C>`, `Operation<T, C>` | `Action<T, C>` | `execute` plus the default `getCompensation` |
| `OperationDef<T, C>` | `ActionDef<T, C>` | the common base, now genuinely common |
| `StepDef`, `SimpleOperationDef` | `StepDef extends ActionDef` | the imperative form |
| `CompositeOperationDef` | `OperationDef extends ActionDef` | the declarative form |
| `ConditionalStepDef` | `ConditionalOperationDef extends ActionDef` | branches, not a member list, so it does not extend `OperationDef` |
| `BoundStep`, `BoundOperation` | `BoundAction(id, action, kind)` | was a sealed marker interface, becomes the record |
| (none) | `ActionKind` | public, `STEP` / `OPERATION` |
| `Component.Step`, `Component.Operation` | `Component.Action` | `permits Component.Action, Component.Condition` |
| `ActionRef` × 6 variants | `ById`, `InlineInstance`, `InlineClass`, `Conditional` | `StepRef` / `OperationRef` and their duplicated `resolve` bodies both go |
| `Transition.step(...)` ×8, `Transition.operation(...)` ×8 | `Transition.run(...)` ×8 | same collapse on `TransitionView` and `TopologyTransition` |
| `composite.step(id)` / `composite.operation(id)` | `run(id)` | by-id members; inline declaration keeps `step(...)` |
| `simpleOperation(...)` / `compositeOperation(...)` | `step(...)` / `operation(...)` | on `StateMachineDef`, `ContextScope` and `TransitionDef` |
| `StepPath` | `ActionPath` | accessors `getExecutedPath()` / `getCompensatedPath()` keep their names, only the element type changes |
| `checkIdNotRegisteredAsStep` / `AsOperation` | (deleted) | one registration map, so the invariant is trivially true |
| "registered as a step, not an operation" | (deleted) | five sites: `ActionRef` ×2, `TransitionView` ×2, `TransitionDefImpl` ×1 |
| `runBoundStep` + `runChildOperation` | `runBoundAction` | the uniform rule |

`Compensation`, `ContextMapper`, `MapperDef`, `BranchDef`, `DefaultBranchDef` and `NoMatchBehavior` move packages but are otherwise untouched, except that the two branch defs get the same by-id/inline verb split as containers - which incidentally makes operations legal inside a branch, where today only steps are.

`compensatedPath` stays leaf-only in practice while being typed one level more generally; that is fine, and it stops being a *rule* once Phase 4 allows containers to compensate.

One thing this deliberately does *not* add: an **inline nested declarative container** (`op.operation("notify-flow", n -> ...)` declaring a whole child container at a member position). It has no equivalent today - you register the container at SM level and reference it - and it becomes natural under the unified model, but it is additive and not needed to land the unification. It is also a parity gap rather than a nicety, because the YAML DSL's §3.1.2 makes inline definitions first-class at every position that accepts a component, so the Java DSL will have to grow this before the two DSLs can claim parity. Tracked in [Phase 5.0](../roadmap/phase-5-yaml-dsl.md).

The completed-phase records under `docs/history/` are left alone. `phase-2-operations-steps-conditions.md` is literally titled after the distinction being collapsed here, and rewriting it would falsify the record of what shipped.

## Migration

The library is pre-1.0 with no published artifacts, so there is no deprecation cycle - the old names are removed, not soft-landed.

For a host, the changes are:

1. `implements Step<T, C>` and `implements Operation<T, C>` both become `implements Action<T, C>`. The method bodies are unchanged; the signature was already the same.
2. `import org.transflux.core.operation.*` becomes `import org.transflux.core.action.*`.
3. `.simpleOperation(id, X.class)` becomes `.step(id, X.class)`; `.compositeOperation(id, cfg)` becomes `.operation(id, cfg)`.
4. Every reference by id becomes `.run(id)` - `transition.step(id)` and `transition.operation(id)` inside an action body, `composite.step(id)` and `composite.operation(id)` at a member position, and `t.operation(registeredId)` on a transition. References that carry a mapper keep their second argument unchanged.
5. `StepPath` becomes `ActionPath` in any code reading `getExecutedPath()` / `getCompensatedPath()`.

And one meaning change worth stating on its own, because it is the kind of thing that silently confuses a reader six months later: **"operation" no longer means "the unit of work attached to a transition". It means "a declarative container".** A transition can attach either form now, and the thing it attaches is an action.
