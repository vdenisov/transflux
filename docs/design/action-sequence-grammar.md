# One grammar for declaring a sequence of actions

> Design note for [Phase 4b](../history/phase-4b-action-sequence-grammar.md). Written before the refactor; reconciled into `requirements.md` when it landed. Two things the phase settled differently from the text below: a transition's body became a sequence rather than staying a single action position, which reversed the "no `fork` at a transition's attachment slot" scope-out; and the `Identifiable` overload family was removed, so the surface is smaller than the counts here suggest.

## Decision

**Problem.** The DSL has three places that hold an ordered list of actions - a declarative container, a conditional branch, and a conditional's default branch - and each admits a different subset of the ways to name an action. A container member may carry a mapper, be forked, or declare a conditional inline; a branch member may do none of those, and cannot nest a conditional at all. There is no way to declare a sequence in place at a member position, so forking a one-off group of actions, or nesting a conditional inside a branch, requires registering a component at state-machine level and referencing it by id. The differences are accidents of what each position needed when it was built, not decisions.

**Design.** Two primitives, and one rule about each.

- An **action position** holds exactly one action. It admits the same ways of naming one everywhere: reference it by id (optionally through a call-site mapper), or declare it in place - a step, a conditional, or a sequence.
- A **sequence** is an ordered list of action positions, and nothing else.

The rule that follows: **the grammar of an action position is invariant; what varies between positions is what the enclosing thing *is*.** A container is a sequence that is also an action, so it carries an id, a context type, compensation, routes and listeners. A branch is a sequence with a condition, belonging to its conditional. A default branch is a sequence with neither. A transition's attachment is a single action position, not a sequence.

In Java the invariant is one self-typed interface, `ActionSequence<T, C, SELF>`, that `OperationDef`, `BranchDef` and `DefaultBranchDef` extend; each member form is declared on it exactly once.

**Rollout.** Phase 4b, interrupting Phase 4: the async items still open there touch the same member grammar, and the Phase 5 YAML mapper is the generic caller the base type exists for. It is additive to the DSL: every call shape that compiles today still compiles.

## What exists today

| Position | reference | forked reference | inline step | inline conditional | inline sequence | call-site mapper |
|---|---|---|---|---|---|---|
| Transition attachment (one action) | yes | - | yes | **no** | yes (`operation`) | no |
| Container member | yes | yes | yes | yes | **no** | yes |
| Branch member | yes | **no** | yes | **no** | **no** | **no** |
| Default-branch member | yes | **no** | yes | **no** | **no** | **no** |

Two implementation facts explain the third and fourth rows. `ResolvedBranch` carries `List<String> stepIds` rather than resolved members, so a branch re-resolves each id against the active scope when it runs, instead of holding bound members with their mappings the way `OperationDefImpl.buildBound` does. And a conditional is built through `InlineRegistrationSink` rather than the sealed `ActionDefImpl` pipeline, which is why it sits outside that hierarchy (see CLAUDE.md).

## What the rule changes

| Change | Why it follows from the rule |
|---|---|
| A branch member may carry a mapper | It is an action position; a container's is allowed to cross a context boundary |
| A branch member may be forked | Same; forking is a property of a position inside a sequence |
| A branch may declare a conditional inline | Recursion falls out - a conditional's arm is a sequence like any other |
| A container member may declare a sequence inline | The missing cell; also what a forked group needs |
| A transition may attach a conditional inline | Its slot is an action position, and a conditional is an action |
| A forked member may be declared inline | `forkStep` / `forkOperation` / `forkConditional`; cheap once every position shares one grammar |

Nothing is removed. The transition slot still takes no `fork`: the enclosing thing does not continue past it, so a transition that forks its whole action is a transition that does nothing, which §4.4.1 of `requirements.md` already says.

## Naming

`requirements.md` §2.2.5 defines an **operation** as a declarative *action* - the ordered member list is its authoring form, not its definition. An inline sequence at a member position is therefore an operation: it has an id, runs its members in order, and can carry a compensation and listeners. Giving it a second name would be a second name for one concept.

A branch is *not* an operation. It has a condition, belongs to its conditional, is not independently referenceable, and is not an action, so it stays its own type. What a branch and a container share is the member grammar, not the contract - which is why the shared type is `ActionSequence` ("a sequence of action positions") rather than a widened `OperationDef`, and why `BranchDef` deliberately does not extend `OperationDef`. Doing that would hand a branch `usingContext`, `withCompensation`, `forException` and the listener family, and "what does a branch's compensation mean, relative to the conditional's own?" is a question with no good answer.

`ActionSequence` carries no `Def` suffix: it is a grammar mixin with no identity of its own, like `ContextScope`, not a def. Hosts see it only in a generic helper; in every configurer lambda they still hold an `OperationDef`, `BranchDef` or `DefaultBranchDef`.

## The erasure wall, and what it rules out

One constraint shapes the declaration verbs, so it goes first: **two declaration forms at the same position must have different method names.** `define(String, Consumer<StepDef<T, C>>)` and `define(String, Consumer<OperationDef<T, C>>)` erase to the same signature and will not compile as overloads, and `fork(String, Consumer<...>)` collides identically with the mapper-taking `fork` overloads. This is the clash CLAUDE.md already records for `action(String, Consumer<StepDef>)` versus `action(String, Consumer<OperationDef>)`.

That rules out a single declaration-only verb - an earlier draft of this document proposed `define(id, configurer)` as a way to stop declaration verbs multiplying per reference kind, and it does not work. It would need `defineStep` / `defineOperation` / `defineConditional` anyway, so it removes no multiplication, and it separates a declaration from the position it runs at, which is the one property in-place declaration exists to provide.

So an inline forked declaration is `forkStep` / `forkOperation` / `forkConditional` - one verb per declared form, mirroring `step` / `operation` / `conditional`, with the `fork` prefix keeping the asynchronous surface together under autocomplete. Roughly ten overloads, declared once on `ActionSequence`. The alternative - `fork` stays reference-only and a one-off group registers at state-machine level - was the recommendation while each position carried its own grammar, since the verbs would have been declared three times over; once they are declared once, consistency with the synchronous declaration verbs is worth the ten methods.

## Implementation shape

The runtime work is concentrated in one place: **a branch must carry bound members rather than ids.** `ResolvedBranch(branchId, condition, List<String> stepIds)` becomes a branch holding the same resolved member records a container holds, and the conditional's executor becomes a member-list executor over them. Once that is true, mappers, forking and nested conditionals inside branches are the same code paths a container already uses - every one of them goes through `ExecutingTransitionImpl.runAction`, which is where compensation capture, path qualification, nesting and listener notification already happen for every action at every depth.

Three consequences worth stating rather than discovering:

- Ids stay globally unique, so deeper inline nesting puts more names into one namespace - already true of inline steps, and the reason the build claims each id.
- Inline nesting terminates by construction: it is literal source, so the definition is a finite tree; only by-id references can form a cycle, which the existing cycle detector already covers.
- `StateMachineDefImpl.definitionForks()` walks only state-machine-level and transition-attached containers, on the premise that a container is never declarable inline. A forked branch member and an inline sequence both break that premise, so the walk has to descend through conditionals, branches and nested containers, or the executor is never built for a definition that forks only from inside one.

## Expressing the invariant in Java

Self-typed base, each member form declared exactly once:

```java
public interface ActionSequence<T, C, SELF extends ActionSequence<T, C, SELF>> {
    SELF run(String id);
    SELF fork(String id);
    SELF step(String id, Action<T, C> action);          // ...and the rest, once
}

public interface OperationDef<T, C> extends ActionDef<T, C>, ActionSequence<T, C, OperationDef<T, C>> { }
public interface BranchDef<T, C> extends ActionSequence<T, C, BranchDef<T, C>> {
    BranchDef<T, C> condition(String registeredConditionId);
}
```

Call sites are identical to today's. The alternative - a non-generic base with each subtype redeclaring every method for the narrowed return - costs roughly sixty stubs and their JavaDoc, and it fails the one caller the base exists for: a *generic* helper keeps the chain type only under the self-typed form, as in `<S extends ActionSequence<T, C, S>> void addAuditing(S sequence)`. The Phase 5 YAML mapper is exactly that caller - it walks a member list and calls the same methods whichever position it is filling - and under redeclaration it degrades to the base type and loses the concrete one. `SELF` appears in three `extends` clauses and nowhere a host writes.

One thing is not optional: a test that reflects over the three types and asserts the member grammar is identical across them. The invariant is the whole point, and nothing else will notice when it breaks.

## Why YAML forces this

`requirements.md` §3.4.2 already flags inline nested operations as a Java-vs-YAML parity gap: YAML makes inline definitions first-class everywhere a component is accepted, and Java does not. The gap is worse than one missing form, because in YAML **a member list is a member list** - there is no type to carry a different grammar, and no IDE offering the subset that happens to be legal at that spot. A document that nests a conditional inside a branch looks exactly as valid as one that does not, so the mapper must either accept it or reject something the grammar plainly admits. Either Java gains the invariant or the two DSLs diverge in a way the YAML side cannot express.

## Scope-outs

| Item | Verdict | Reason |
|---|---|---|
| Making `BranchDef` extend `OperationDef` | No | Imports a surface most of which is meaningless on a branch |
| Giving a branch its own compensation | No | Ambiguous against the conditional's own; the conditional is the action |
| `fork` at a transition's attachment slot | No | Nothing continues past it |
| A distinct type for "inline sequence" | No | It is an operation |
| Renaming `OperationDef` so "operation" can name the shared grammar | No | Flips §2.2.5's definition from "declarative action" to "ordered list", and renames the type hosts write most to free a name for one they almost never write |
| A single declaration-only verb (`define`) | No | Erasure forces one verb per declared form anyway, and it separates a declaration from where it runs |
| Changing id scoping or uniqueness | No | Inline nesting already claims ids the same way |

## Sequencing

Tracked as checkboxes in the [Phase 4b record](../history/phase-4b-action-sequence-grammar.md); the dependency shape is:

| # | Work | Size | Depends on |
|---|---|---|---|
| 1 | Branches carry bound members instead of ids; conditional executor becomes a member-list executor | M | - |
| 2 | `ActionSequence` extracted; branch members gain mapper, fork and inline conditional; invariant test | M | 1 |
| 3 | Inline sequence at a member position; `definitionForks()` descends | M | 1 |
| 4 | Transition slot accepts an inline conditional | S | - |
| 5 | Drop the `Identifiable` overloads and `OperationDef.usingContext`; a context is declared where the action is | M | 3 |
| 6 | `forkStep` / `forkOperation` / `forkConditional` | S | 2, 3, 5 |
| 7 | Reconcile `requirements.md` §3.4.2, the YAML grammar, CLAUDE.md and the README | S | 2, 3, 4, 5, 6 |
