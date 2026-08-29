# One grammar for declaring a sequence of actions

Status: proposal. Nothing here is implemented, and none of it changes behaviour that exists today.

## Decision

**Problem.** The DSL has three places that hold an ordered list of actions - a declarative container, a conditional branch, and a conditional's default branch - and each admits a different subset of the ways to name an action. A container member may carry a mapper, be forked, or declare a conditional inline; a branch member may do none of those, and cannot nest a conditional at all. There is no way to declare a sequence in place at a member position, so forking a one-off group of actions, or nesting a conditional inside a branch, requires registering a component at state-machine level and referencing it by id. The differences are accidents of what each position needed when it was built, not decisions.

**Design.** Two primitives, and one rule about each.

- An **action position** holds exactly one action. It admits the same ways of naming one everywhere: reference it by id (optionally through a call-site mapper), or declare it in place - a step, a conditional, or a sequence.
- A **sequence** is an ordered list of action positions, and nothing else.

The rule that follows: **the grammar of an action position is invariant; what varies between positions is what the enclosing thing *is*.** A container is a sequence that is also an action, so it carries an id, a context type, compensation, routes and listeners. A branch is a sequence with a condition, belonging to its conditional. A default branch is a sequence with neither. A transition's attachment is a single action position, not a sequence.

**Rollout.** This lands with Phase 5, which forces the question anyway (below), or earlier if forking a group turns out to be common. It is additive to the DSL: every call shape that compiles today still compiles.

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

Nothing is removed. The transition slot still takes no `fork`: the enclosing thing does not continue past it, so a transition that forks its whole action is a transition that does nothing, which §4.4.1 of `requirements.md` already says.

## Naming

An inline sequence at a member position is **an operation** - it has an id, runs its members in order, and can carry a compensation and listeners, which is the definition of one. Giving it a second name would be a second name for one concept.

What is *not* an operation is a branch. It has a condition, belongs to its conditional, and is not independently referenceable, so it stays its own type. The shared thing between them is the member grammar, not the contract - which is why this proposal unifies the grammar and deliberately does not make `BranchDef` extend `OperationDef`. Doing that would hand a branch `usingContext`, `withCompensation`, `forException` and the listener family, and "what does a branch's compensation mean, relative to the conditional's own?" is a question with no good answer.

## The erasure wall, and what it rules out

One constraint shapes every option below, so it goes first: **two declaration forms at the same position must have different method names.** `define(String, Consumer<StepDef<T, C>>)` and `define(String, Consumer<OperationDef<T, C>>)` erase to the same signature and will not compile as overloads, and `fork(String, Consumer<...>)` collides identically. This is the clash CLAUDE.md already records for `action(String, Consumer<StepDef>)` versus `action(String, Consumer<OperationDef>)`.

That rules out a single declaration-only verb - an earlier draft of this document proposed `define(id, configurer)` as a way to stop declaration verbs multiplying per reference kind, and it does not work. It would need `defineStep` / `defineOperation` / `defineConditional` anyway, so it removes no multiplication, and it separates a declaration from the position it runs at, which is the one property in-place declaration exists to provide.

So a forked inline group has exactly two honest shapes:

| Shape | Cost |
|---|---|
| `fork` stays reference-only | A one-off group needs a state-machine-level registration and a global id. Forked work is typically a reusable flow already, so this is often no cost at all |
| `forkStep` / `forkOperation` / `forkConditional` | Roughly ten overloads, and a declaration verb per reference kind - but locality is preserved, and every call site reads where it runs |

Recommendation: stay reference-only until a real definition wants otherwise. The second shape is additive whenever that happens.

## Implementation shape

The runtime work is concentrated in one place: **a branch must carry bound members rather than ids.** `ResolvedBranch(branchId, condition, List<String> stepIds)` becomes a branch holding the same resolved member records a container holds, and the conditional's executor becomes a member-list executor over them. Once that is true, mappers, forking and nested conditionals inside branches are the same code paths a container already uses - every one of them goes through `ExecutingTransitionImpl.runAction`, which is where compensation capture, path qualification, nesting and listener notification already happen for every action at every depth.

Two consequences worth stating rather than discovering. Ids stay globally unique, so deeper inline nesting puts more names into one namespace - already true of inline steps, and the reason the build claims each id. And inline nesting terminates by construction: it is literal source, so the definition is a finite tree; only by-id references can form a cycle, which the existing cycle detector already covers.

## Expressing the invariant in Java

Two ways, and the choice is open.

**Self-typed base** - each member form declared exactly once:

```java
public interface MemberSequence<T, C, SELF extends MemberSequence<T, C, SELF>> {
    SELF run(String id);
    SELF fork(String id);
    SELF step(String id, Action<T, C> action);          // ...and the rest, once
}

public interface OperationDef<T, C> extends ActionDef<T, C>, MemberSequence<T, C, OperationDef<T, C>> { }
public interface BranchDef<T, C> extends MemberSequence<T, C, BranchDef<T, C>> {
    BranchDef<T, C> condition(String registeredConditionId);
}
```

**Redeclaring subtypes** - house style, no new generics:

```java
public interface MemberSequence<T, C> {
    MemberSequence<T, C> run(String id);                 // ...and the rest
}

public interface OperationDef<T, C> extends ActionDef<T, C>, MemberSequence<T, C> {
    @Override OperationDef<T, C> run(String id);         // ~20 of these, per subtype
    @Override OperationDef<T, C> fork(String id);
}
```

Call sites are identical under both. The trade is roughly sixty redeclaration stubs and their JavaDoc against one recursive type parameter in three `extends` clauses.

The tiebreaker is a caller neither shape makes obvious: only the self-typed form lets a *generic* helper keep the chain type, as in `<S extends MemberSequence<T, C, S>> void addAuditing(S sequence)`. The Phase 5 YAML mapper is exactly that caller - it walks a member list and calls the same methods whichever position it is filling - and under redeclaration it degrades to `MemberSequence<T, C>` and loses the concrete type.

Whichever is chosen, one thing is not optional: a test that reflects over the three types and asserts the member grammar is identical across them. The invariant is the whole point, and nothing else will notice when it breaks.

## Why YAML forces this

`requirements.md` §3.4.2 already flags inline nested operations as a Java-vs-YAML parity gap: YAML makes inline definitions first-class everywhere a component is accepted, and Java does not. The gap is worse than one missing form, because in YAML **a member list is a member list** - there is no type to carry a different grammar, and no IDE offering the subset that happens to be legal at that spot. A document that nests a conditional inside a branch looks exactly as valid as one that does not, so the mapper must either accept it or reject something the grammar plainly admits. Either Java gains the invariant or the two DSLs diverge in a way the YAML side cannot express.

## Scope-outs

| Item | Verdict | Reason |
|---|---|---|
| Making `BranchDef` extend `OperationDef` | No | Imports a surface most of which is meaningless on a branch |
| Giving a branch its own compensation | No | Ambiguous against the conditional's own; the conditional is the action |
| `fork` at a transition's attachment slot | No | Nothing continues past it |
| A distinct type for "inline sequence" | No | It is an operation |
| A single declaration-only verb (`define`) | No | Erasure forces one verb per declared form anyway, and it separates a declaration from where it runs |
| Changing id scoping or uniqueness | No | Inline nesting already claims ids the same way |

## Open questions

| Question | Who answers | What changes with the answer |
|---|---|---|
| Self-typed base or redeclaring subtypes? | Owner, at implementation time | Roughly sixty method redeclarations, against a generic parameter in three public signatures |
| Does `fork` stay reference-only, or gain `forkStep` / `forkOperation` / `forkConditional`? | Owner, when a forked one-off group is first wanted | Roughly ten overloads against one registration per forked group |
| Does this land in one change or per position? | Owner, at scheduling time | Whether branches gain the full grammar before or with the inline-sequence form |

## Sequencing

| # | Work | Size | Depends on |
|---|---|---|---|
| 1 | Branches carry bound members instead of ids; conditional executor becomes a member-list executor | M | - |
| 2 | Branch members gain mapper and fork support | S | 1 |
| 3 | Branches may declare a conditional inline (recursion) | S | 1 |
| 4 | Inline sequence at a member position | M | 1 |
| 5 | Fork-specific declaration verbs, only if reference-only proves insufficient | M | 4 |
| 6 | Transition slot accepts an inline conditional | S | - |
| 7 | Invariant test over the three sequence types; reconcile `requirements.md` §3.4.2 and the YAML grammar | S | 2, 3, 4 |
