# Sample PlanDefinition — Diabetes Management (Comprehensive Example)

This document provides a complete, annotated `PlanDefinition` that exercises every feature parsed by the matcher service (`PlanDefinitionParser`), along with companion `ActivityDefinition` resources.

---

## PlanDefinition JSON

```json
{
  "resourceType": "PlanDefinition",
  "id": "diabetes-management-comprehensive",
  "url": "http://openphc.org/PlanDefinition/diabetes-management-comprehensive",
  "version": "1.0.0",
  "name": "DiabetesManagementComprehensive",
  "title": "Diabetes Care — Comprehensive Management Protocol",
  "status": "active",
  "date": "2026-04-21",
  "description": "Protocol for managing diabetic patients. Covers enrollment, HbA1c, blood glucose, foot exam, and multi-level intelligence escalation actions.",

  "action": [

    {
      "id": "enrollment",
      "title": "Enroll Patient on Diabetes Diagnosis",
      "description": "Enroll when a confirmed active diabetes Condition is created.",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "diagnosis-trigger",
          "data": [
            {
              "type": "Condition",
              "codeFilter": [
                {
                  "path": "code",
                  "code": [
                    { "system": "http://snomed.info/sct",          "code": "73211009" },
                    { "system": "http://hl7.org/fhir/sid/icd-10",  "code": "E11" }
                  ]
                },
                {
                  "path": "clinicalStatus",
                  "code": [
                    {
                      "system": "http://terminology.hl7.org/CodeSystem/condition-clinical",
                      "code": "active"
                    }
                  ]
                }
              ]
            }
          ],
          "condition": {
            "language": "text/fhirpath",
            "expression": "Condition.verificationStatus.coding.where(code = 'confirmed').exists()"
          }
        }
      ],
      "timingTiming": {
        "repeat": { "count": 1, "frequency": 1, "period": 1, "periodUnit": "d" }
      }
    },

    {
      "id": "hba1c-check",
      "title": "HbA1c Monitoring",
      "requiredBehavior": "must",
      "description": "Quarterly HbA1c test to evaluate glycaemic control.",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "hba1c-trigger",
          "data": [
            {
              "type": "Observation",
              "codeFilter": [
                {
                  "path": "code",
                  "code": [
                    { "system": "http://loinc.org", "code": "4548-4" }
                  ]
                }
              ]
            }
          ]
        }
      ],
      "timingTiming": {
        "repeat": { "count": 4, "frequency": 1, "period": 90, "periodUnit": "d" }
      },
      "extension": [
        {
          "url": "http://openphc.org/fhir/StructureDefinition/tolerance-days",
          "valueInteger": 7
        }
      ],
      "relatedAction": [
        {
          "actionId": "enrollment",
          "relationship": "after-end",
          "offsetDuration": {
            "value": 30, "unit": "d",
            "system": "http://unitsofmeasure.org", "code": "d"
          }
        }
      ],
      "action": [
        {
          "id": "hba1c-elevated-alert",
          "title": "Elevated HbA1c — Worker Alert",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/fhirpath",
                "expression": "Observation.value.value > 7.0"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glycaemic-worker-alert|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "MEDIUM"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "ASSIGNED_WORKER"
            }
          ]
        },
        {
          "id": "hba1c-critical-escalation",
          "title": "Critical HbA1c — Supervisor Escalation",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/fhirpath",
                "expression": "Observation.value.value > 10.0"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glycaemic-supervisor-escalation|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "CRITICAL"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "SUPERVISOR"
            }
          ]
        },
        {
          "id": "hba1c-missed-deviation",
          "title": "HbA1c Missed — Worker Alert",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\"==\": [{\"var\": \"event.deviationType\"}, \"missed\"]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glycaemic-worker-alert|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "HIGH"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "ASSIGNED_WORKER"
            }
          ]
        },
        {
          "id": "hba1c-missed-deviation",
          "title": "HbA1c Missed — Supervisor Escalation",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\"==\": [{\"var\": \"event.deviationType\"}, \"missed\"]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glycaemic-supervisor-escalation|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "CRITICAL"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "SUPERVISOR"
            }
          ]
        }
      ]
    },

    {
      "id": "blood-glucose-check",
      "title": "Fasting Blood Glucose Check",
      "requiredBehavior": "must",
      "description": "Weekly fasting blood glucose observation.",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "glucose-trigger",
          "data": [
            {
              "type": "Observation",
              "codeFilter": [
                {
                  "path": "code",
                  "code": [
                    { "system": "http://loinc.org", "code": "1558-6" }
                  ]
                }
              ]
            }
          ]
        }
      ],
      "timingTiming": {
        "repeat": { "count": 12, "frequency": 1, "period": 7, "periodUnit": "d" }
      },
      "extension": [
        {
          "url": "http://openphc.org/fhir/StructureDefinition/tolerance-days",
          "valueInteger": 2
        }
      ],
      "relatedAction": [
        {
          "actionId": "hba1c-check",
          "relationship": "after-end",
          "offsetDuration": {
            "value": 7, "unit": "d",
            "system": "http://unitsofmeasure.org", "code": "d"
          }
        }
      ],
      "action": [
        {
          "id": "glucose-low-patient-info",
          "title": "Low Glucose — Patient Information",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\"<\": [{\"var\": \"glucoseValue\"}, 4.0]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glucose-patient-notification|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "LOW"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "PATIENT"
            }
          ]
        },
        {
          "id": "glucose-high-worker-task",
          "title": "High Glucose — Worker Follow-up Task",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\">=\": [{\"var\": \"glucoseValue\"}, 11.1]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glucose-worker-task|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "HIGH"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "ASSIGNED_WORKER"
            }
          ]
        },
        {
          "id": "glucose-critical-facility-referral",
          "title": "Critical Glucose — Facility Emergency Referral",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\">=\": [{\"var\": \"glucoseValue\"}, 22.2]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/glucose-emergency-referral|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "CRITICAL"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "FACILITY"
            }
          ]
        }
      ]
    },

    {
      "id": "annual-foot-exam",
      "title": "Annual Diabetic Foot Examination",
      "description": "Mandatory annual foot exam — must not be skipped.",
      "requiredBehavior": "must",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "foot-exam-trigger",
          "data": [
            {
              "type": "Observation",
              "codeFilter": [
                {
                  "path": "code",
                  "code": [
                    { "system": "http://loinc.org", "code": "44963-7" }
                  ]
                }
              ]
            }
          ]
        }
      ],
      "timingTiming": {
        "repeat": { "count": 1, "frequency": 1, "period": 365, "periodUnit": "d" }
      },
      "extension": [
        {
          "url": "http://openphc.org/fhir/StructureDefinition/tolerance-days",
          "valueInteger": 14
        }
      ],
      "action": [
        {
          "id": "foot-exam-missed-supervisor",
          "title": "Missed Foot Exam — Supervisor Escalation",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\"==\": [{\"var\": \"event.deviationType\"}, \"missed\"]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/foot-exam-missed-alert|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "CRITICAL"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "SUPERVISOR"
            }
          ]
        }
      ]
    },

    {
      "id": "any-encounter-log",
      "title": "Log Any Patient Encounter",
      "description": "Broadest match — data[] type only, no codeFilter. Produces one TriggerIndex row with empty path/system/code.",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "any-encounter-trigger",
          "data": [
            { "type": "Encounter" }
          ]
        }
      ]
    },

    {
      "id": "finished-encounter-match",
      "title": "Matcher Check on Finished Encounter",
      "description": "Resource type match combined with an inline trigger condition (Scenario 3: F1 + F3).",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "finished-encounter-trigger",
          "data": [
            { "type": "Encounter" }
          ],
          "condition": {
            "language": "text/fhirpath",
            "expression": "Encounter.status = 'finished'"
          }
        }
      ]
    },

    {
      "id": "global-risk-assessment",
      "title": "Global Risk Score Assessment",
      "description": "Condition-only trigger — no data[]. Evaluated on every inbound event (Scenario 5: F3 only). No TriggerIndex row created.",
      "type": {
        "coding": [
          { "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }
        ]
      },
      "trigger": [
        {
          "type": "named-event",
          "name": "risk-score-trigger",
          "condition": {
            "language": "text/jsonlogic",
            "expression": "{\">=\": [{\"var\": \"riskScore\"}, 8]}"
          }
        }
      ],
      "action": [
        {
          "id": "global-risk-worker-notification",
          "title": "High Risk Score — Worker Notification",
          "type": {
            "coding": [
              { "system": "http://terminology.hl7.org/CodeSystem/action-type", "code": "fire-event" }
            ]
          },
          "condition": [
            {
              "kind": "applicability",
              "expression": {
                "language": "text/jsonlogic",
                "expression": "{\">=\": [{\"var\": \"riskScore\"}, 8]}"
              }
            }
          ],
          "definitionCanonical": "http://openphc.org/ActivityDefinition/risk-score-worker-alert|1.0.0",
          "extension": [
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
              "valueCode": "HIGH"
            },
            {
              "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
              "valueCode": "ASSIGNED_WORKER"
            }
          ]
        }
      ]
    }

  ]
}
```

---

## Companion ActivityDefinitions

Each `definitionCanonical` referenced in the PlanDefinition must have a registered `ActivityDefinition`. The `kind` field maps to the `ActionDefinitionKind` enum (`CommunicationRequest`, `Task`, `ServiceRequest`).

### CommunicationRequest — Supervisor Escalation

```json
{
  "resourceType": "ActivityDefinition",
  "id": "glycaemic-supervisor-escalation",
  "url": "http://openphc.org/ActivityDefinition/glycaemic-supervisor-escalation",
  "version": "1.0.0",
  "name": "GlycaemicSupervisorEscalation",
  "title": "Glycaemic Supervisor Escalation",
  "status": "active",
  "kind": "CommunicationRequest",
  "description": "Sends a supervisor escalation message when glycaemic thresholds are breached or a step is missed.",
  "extension": [
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
      "valueCode": "CRITICAL"
    },
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
      "valueCode": "SUPERVISOR"
    }
  ]
}
```

### Task — Worker Follow-up

```json
{
  "resourceType": "ActivityDefinition",
  "id": "glucose-worker-task",
  "url": "http://openphc.org/ActivityDefinition/glucose-worker-task",
  "version": "1.0.0",
  "name": "GlucoseWorkerTask",
  "title": "High Glucose Worker Follow-up Task",
  "status": "active",
  "kind": "Task",
  "description": "Creates a follow-up task for the assigned community health worker when blood glucose is elevated.",
  "extension": [
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
      "valueCode": "HIGH"
    },
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
      "valueCode": "ASSIGNED_WORKER"
    }
  ]
}
```

### ServiceRequest — Emergency Referral

```json
{
  "resourceType": "ActivityDefinition",
  "id": "glucose-emergency-referral",
  "url": "http://openphc.org/ActivityDefinition/glucose-emergency-referral",
  "version": "1.0.0",
  "name": "GlucoseEmergencyReferral",
  "title": "Critical Glucose Emergency Referral",
  "status": "active",
  "kind": "ServiceRequest",
  "description": "Generates an emergency service referral to the facility when glucose is critically high.",
  "extension": [
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-severity",
      "valueCode": "CRITICAL"
    },
    {
      "url": "http://openphc.org/fhir/StructureDefinition/intelligence-destination",
      "valueCode": "FACILITY"
    }
  ]
}
```

---

## Feature Reference

| Feature | Field / Extension | Allowed Values |
|---|---|---|
| Action type coding (required on every action, all nesting levels) | `action[].type.coding` | `step` (`http://openphc.org/fhir/CodeSystem/action-type`) or `fire-event` (`http://terminology.hl7.org/CodeSystem/action-type`) |
| Trigger — resource + code filter | `trigger[].data[].codeFilter[]` | One row per code in `TriggerIndex` |
| Trigger — inline condition | `trigger[].condition` | `text/fhirpath`, `text/jsonlogic` |
| Trigger — condition only (no data) | `trigger[]` with no `data[]` | Placed in `extractConditionOnlyTriggers()` |
| Intelligence sub-action condition | `action[].condition[kind=applicability]` | `text/fhirpath`, `text/jsonlogic` |
| Intelligence severity | `intelligence-severity` extension | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| Intelligence destination | `intelligence-destination` extension | Free-form string (routing destination for the Intelligence Service). This example uses `PATIENT`, `ASSIGNED_WORKER`, `SUPERVISOR`, `FACILITY` |
| Deviation type (jsonlogic var) | `event.deviationType` | `"missed"`, `"order_violation"`. **Not** `"overdue"`. Reaching `sla_status = OVERDUE` raises no deviation, so an action conditioned on `"overdue"` never fires |
| Step tolerance window | `tolerance-days` extension | Integer (days) |
| Required step | `requiredBehavior` | `must`, `could`, `must-unless-documented`. `must` is the only mandatory value, and the only one that may carry a deadline (see note below) |
| Step scheduling | `timingTiming.repeat` | `count`, `frequency`, `period`, `periodUnit` |
| Action ordering | `relatedAction[].actionId` + `offsetDuration` | The step names its **prerequisite**. `offsetDuration` is how long after that prerequisite this step is due |
| ActivityDefinition type | `kind` | `CommunicationRequest`, `Task`, `ServiceRequest` |
| Canonical reference format | `definitionCanonical` | `<url>|<version>` |
| Step group | `groupingBehavior` on a `step`-typed action with child actions | `logical-group` makes it a group (see [Step Groups](#step-groups)). `visual-group` and `sentence-group` keep it a plain step |
| Repeating by visit | `extension` `cycle-boundary` on a step group, `valueCode` `calendar-day` or `entry-step` | A new cycle each day the patient comes, or with each event for its entry step. Only an event opens such a group, never its predecessor or parent (see [Step Groups](#step-groups)) |
| Entry step | `extension` `cycle-entry-step` on an `entry-step` group. Required, exactly once. `valueId` is a step's action id | The step that starts each cycle. It is a step inside the group, directly or in a nested group, and waits on nothing inside the group (see [Step Groups](#step-groups)) |

`requiredBehavior` note: **only `must` is mandatory, and an absent value is not.** `must` drives the "must"-only predecessor backfill gating in `MandatoryStepBackfillService.backfillMissingMandatorySteps` (via `PlanDefinitionParser.computeMustPredecessorSteps`). It is also the only value that gets an SLA schedule. A step that is not `must`:

- is never pre-created by progressive instantiation (only materialized on the fly if its own trigger fires)
- gets no `step_sla_state_transition` rows
- can therefore be neither `OVERDUE` nor `MISSED`

Nothing was required of it, so there is nothing for it to breach. The one definition is `RequiredBehavior.isMandatory`.

Consequently **`tolerance-days` on an action that is not `must` is rejected at load** by the Protocol Service. It would enforce nothing while looking as though it does.

`must-unless-documented` is a valid FHIR code and is still accepted (see the `data-dictionary.md` `required_behavior` check constraint). But nothing branches on it. So it is optional like `could`, deadlines included.

---

## Trigger Matching Scenarios

| Scenario | `data[]` present | `codeFilter[]` present | `condition` present | Parser handling |
|---|---|---|---|---|
| 1 | Yes | No | No | `TriggerIndex` row with empty path/system/code |
| 2 | Yes | Yes | No | One `TriggerIndex` row per code |
| 3 | Yes | No | Yes | `TriggerIndex` row (empty) + in-memory condition check |
| 4 | Yes | Yes | Yes | `TriggerIndex` rows per code + in-memory condition check |
| 5 | No | — | Yes | `extractConditionOnlyTriggers()` — evaluated on every event |

---

## Step Groups

A step group is a set of steps that belong together clinically, such as one antenatal-care visit. It is written as a `step`-typed action with:

- `groupingBehavior: logical-group`
- child actions
- no trigger

The group itself is never matched to an event. Only its steps are. How the Matcher Service opens and tracks a group is in [Architecture Overview §6.5](architecture-overview.md#65-step-groups).

**For now, every group is optional.** A group can't declare `requiredBehavior`; the Protocol Service refuses one at load. Mandatory groups come in a later phase. So a group never opens with its parent, or when a step it follows completes. It opens on an event for one of its steps, or when work creates one of its `must` steps. To have a group's steps expected after an earlier step, put the `relatedAction` on those steps, as below.

```json
{
  "id": "anc-visit-2",
  "title": "ANC visit 2",
  "type": { "coding": [{ "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }] },
  "groupingBehavior": "logical-group",
  "action": [
    {
      "id": "anc2-bp",
      "title": "Blood pressure",
      "type": { "coding": [{ "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }] },
      "requiredBehavior": "must",
      "relatedAction": [
        {
          "actionId": "anc-registration",
          "relationship": "after-end",
          "offsetDuration": {
            "value": 6, "unit": "wk",
            "system": "http://unitsofmeasure.org", "code": "wk"
          }
        }
      ],
      "extension": [
        { "url": "http://openphc.org/fhir/StructureDefinition/tolerance-days", "valueInteger": 14 }
      ],
      "trigger": [{
        "type": "named-event", "name": "anc2-bp-trigger",
        "data": [{ "type": "Observation",
                   "codeFilter": [{ "path": "code", "code": [{ "system": "http://loinc.org", "code": "85354-9" }] }] }]
      }]
    },
    {
      "id": "anc2-weight",
      "title": "Body weight",
      "type": { "coding": [{ "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }] },
      "requiredBehavior": "must",
      "relatedAction": [
        {
          "actionId": "anc-registration",
          "relationship": "after-end",
          "offsetDuration": {
            "value": 6, "unit": "wk",
            "system": "http://unitsofmeasure.org", "code": "wk"
          }
        }
      ],
      "extension": [
        { "url": "http://openphc.org/fhir/StructureDefinition/tolerance-days", "valueInteger": 14 }
      ],
      "trigger": [{
        "type": "named-event", "name": "anc2-weight-trigger",
        "data": [{ "type": "Observation",
                   "codeFilter": [{ "path": "code", "code": [{ "system": "http://loinc.org", "code": "29463-7" }] }] }]
      }]
    },
    {
      "id": "anc2-counselling",
      "title": "Counselling",
      "type": { "coding": [{ "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }] },
      "requiredBehavior": "could",
      "trigger": [{
        "type": "named-event", "name": "anc2-counselling-trigger",
        "data": [{ "type": "Procedure",
                   "codeFilter": [{ "path": "code", "code": [{ "system": "http://snomed.info/sct", "code": "61310001" }] }] }]
      }]
    }
  ]
}
```

What this example does, given a mandatory `anc-registration` step elsewhere in the PlanDefinition:
- **When registration completes,** BP and weight are created, due six weeks later. Each is a `must` step whose `relatedAction` names registration. Creating them opens the visit. With their own `tolerance-days`, they can go `OVERDUE` and then `MISSED`.
- **The visit declares no `requiredBehavior`,** so it is optional, as every group is for now. Had the `relatedAction` been on the visit instead of its steps, registration completing would open nothing: it would only plan the visit's due date. BP and weight would then not be expected until the patient came.
- **Counselling is optional,** so it is never created in advance. It is created only when its own event arrives.
- **If a BP event arrives before registration,** the visit opens then, and registration is backfilled, awaiting. Weight waits on registration, so it isn't created yet. When registration is done, weight is created, due six weeks later.
- **A second BP event** after the first is recorded as a further completed row in the same visit.

**Repeating by visit.** A group repeats in cycles when it carries the `cycle-boundary` extension. Below is a patient journey with one cycle per visit day. It holds a Consultation that can happen twice in one visit. Triggers are left out:

```json
{
  "id": "journey",
  "title": "Patient journey",
  "type": { "coding": [{ "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }] },
  "groupingBehavior": "logical-group",
  "extension": [
    { "url": "http://openphc.org/fhir/StructureDefinition/cycle-boundary", "valueCode": "calendar-day" }
  ],
  "action": [
    { "id": "check-in", "title": "Check-in", "requiredBehavior": "must", "...": "type, trigger" },
    {
      "id": "consultation",
      "title": "Consultation",
      "type": { "coding": [{ "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }] },
      "groupingBehavior": "logical-group",
      "extension": [
        { "url": "http://openphc.org/fhir/StructureDefinition/cycle-boundary",   "valueCode": "entry-step" },
        { "url": "http://openphc.org/fhir/StructureDefinition/cycle-entry-step", "valueId": "history" }
      ],
      "relatedAction": [{ "actionId": "check-in", "relationship": "after-end" }],
      "action": [
        { "id": "history", "title": "History", "requiredBehavior": "must", "...": "type, trigger" },
        { "id": "examination", "title": "Examination", "requiredBehavior": "must",
          "relatedAction": [{ "actionId": "history", "relationship": "after-end" }], "...": "type, trigger" }
      ]
    }
  ]
}
```

What this example does:
- **The first event of a day** opens that day's visit, which is a new journey cycle. Any other event that day joins it. Days are counted in the deployment's time zone (`CCE_CALENDAR_ZONE`). An event dated on an earlier day that arrives late joins the latest visit.
- **Check-in completing doesn't open the Consultation,** although it follows check-in. A group that repeats by visit opens only for an event for one of its steps. Until then, history is not awaited or judged.
- **Each history starts a Consultation.** The first in a visit starts the first. A second history in that visit starts a second. An examination joins the current Consultation, as a further row if it already has one. An examination before any history in the visit starts the Consultation. Examination follows history, so history is backfilled there, awaiting. The history that follows completes it.
- **Only cycle 0 has a planned due date.** A visit or Consultation opened because the patient came back has none, so its awaited steps are not judged.
- **A step in the journey that follows a step outside it,** such as consent 2 days after registration, keeps its own offset. In the first visit, consent is due 2 days after registration. Each later visit creates consent again, with no due date. A step that should happen only once belongs outside the journey.

**The entry step.** The entry step is the step that starts each cycle. An `entry-step` group must name it by action id in the `cycle-entry-step` extension. The Consultation above names history. The entry step decides only when a cycle starts. It does not decide the order of the steps, so another step can still wait on nothing. Below is a Consultation where BP can be taken before history, but only history starts a new Consultation:

```json
{
  "id": "consultation",
  "title": "Consultation",
  "type": { "coding": [{ "system": "http://openphc.org/fhir/CodeSystem/action-type", "code": "step" }] },
  "groupingBehavior": "logical-group",
  "extension": [
    { "url": "http://openphc.org/fhir/StructureDefinition/cycle-boundary",   "valueCode": "entry-step" },
    { "url": "http://openphc.org/fhir/StructureDefinition/cycle-entry-step", "valueId": "history" }
  ],
  "action": [
    { "id": "history", "title": "History", "requiredBehavior": "must", "...": "type, trigger" },
    { "id": "bp", "title": "Blood pressure", "requiredBehavior": "must", "...": "type, trigger" }
  ]
}
```

What this example does:
- **A second BP** joins the current Consultation as a further completed BP, since BP is not the entry step.
- **Each history starts the next Consultation.**
- **A BP before any history** starts the first Consultation, without history. A cycle's opening never creates the entry step. The history that follows starts the next Consultation.
- **An entry step with a `relatedAction` to a step outside the group,** such as history 2 days after check-in, is expected. When that step completes, the first Consultation opens with history `NOT_STARTED`, due by its offset. The first history completes it.
- **The extension is required, exactly once.** An `entry-step` group without it, or with it more than once, is refused at load.
- **The entry step must be a step inside the group, waiting on nothing inside it.** It can be directly in the group or in one of its nested groups. A group, a step outside the group, or a step with a `relatedAction` to another action in the group is refused at load.

The Protocol Service rejects at load:

- a group that has a trigger or no step
- `requiredBehavior` on a group: every group is optional for now
- a group with a `selectionBehavior` other than `all`
- `tolerance-days` on a group
- a `cycle-boundary` code other than `calendar-day` or `entry-step`
- a `calendar-day` group inside another `calendar-day` group, at any depth
- an `entry-step` group that:
  - does not have exactly one `cycle-entry-step`
  - has no `valueId`
  - names anything but a step inside the group, directly or in a nested group, with no predecessor inside the group
- `cycle-entry-step` on any other action

It also refuses the following, until they are built:

- `timing` on a group (scheduled repeats)
- `tolerance-days` on a group (group deadlines)
- a `fire-event` child of a group
- any action that follows a group
