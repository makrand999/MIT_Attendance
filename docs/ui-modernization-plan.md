# MIT Attendance UI Modernization Plan

## Goals

- Make the app easier to understand on first launch.
- Reduce dependence on the navigation drawer for core actions.
- Add proper swipe navigation for the most-used areas.
- Surface important information immediately: overall attendance, risky subjects, alerts, and shortcuts.
- Improve consistency across cards, spacing, hierarchy, and feedback states.

## Current UX Gaps

- Core features are buried inside the drawer, so discoverability is weak.
- The home screen is mostly a list, with limited context and weak prioritization.
- Swipe behavior exists for opening the drawer, but not for moving between major home sections.
- The feature set is broad, but the information architecture does not reflect that breadth.
- UI patterns vary between screens, which makes the app feel less predictable.

## Rollout Plan

### Phase 1: Swipeable Home Shell

Status: In progress

- Convert `SubjectsActivity` into a swipeable home shell.
- Add top-level pages for:
  - Overview
  - Subjects
  - Tools
- Keep the drawer for secondary actions, but move high-frequency actions into visible quick actions.
- Show summary cards for attendance health, subject count, low-attendance subjects, and notification state.
- Preserve the existing subject list and detail flow to avoid breaking current behavior.

### Phase 2: Consistent Component Pass

- Standardize cards, spacing, icon usage, and typography across home-related screens.
- Improve empty states, loading states, and refresh feedback.
- Replace dense text-only entry points with clearer feature cards and section labels.
- Tighten contrast and hierarchy so scanability improves on smaller devices.

### Phase 3: Feature Navigation Upgrade

- Introduce more consistent motion between related screens.
- Add contextual entry points from overview cards into alerts, timetable, practicals, and reviews.
- Reduce back-and-forth navigation by promoting common actions directly from the home shell.
- Review whether some drawer actions should move to visible tabs, chips, or action groups.

### Phase 4: Screen-by-Screen Polish

- Refine Alerts, Practicals, Certifications, and Notes for consistency with the new home shell.
- Improve form ergonomics and action placement on create/submit flows.
- Add stronger status badges, section headers, and contextual filters where useful.
- Audit gesture conflicts and ensure swipe patterns remain predictable.

## Implementation Order

1. Build the swipeable home shell in `SubjectsActivity`.
2. Reuse the current subject list inside the new home structure.
3. Add overview metrics and quick action cards.
4. Keep the navigation drawer as a fallback and secondary menu.
5. Validate the build before moving to the next screen pass.

## Success Criteria

- A new user can discover the primary features without opening the drawer first.
- The home screen communicates overall status within a few seconds.
- Horizontal swipes move between major home sections reliably.
- Existing subject-detail behavior continues to work.
- The redesign improves access without removing current functionality.
