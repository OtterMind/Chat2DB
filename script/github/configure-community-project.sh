#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="${ROOT_DIR}/.github/issue-taxonomy.json"
OWNER="${GH_PROJECT_OWNER:-$(jq -r '.project.owner' "${MANIFEST}")}"
REPOSITORY="$(jq -r '.project.repository' "${MANIFEST}")"
PROJECT=""
APPLY=false
API_VERSION="${GH_API_VERSION:-2026-03-10}"

usage() {
  cat <<'EOF'
Usage: configure-community-project.sh [--owner OWNER] [--project NUMBER] [--apply]

Without --apply, the script prints the desired Project configuration.
With --apply, it creates or updates the public Project, repository link,
Status field, Priority field, and saved views. Built-in workflows remain a
one-time GitHub UI step. The UI is also required to add Issue Type columns and
remove the default view because GitHub exposes no update API for those settings.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --owner)
      if [[ $# -lt 2 || -z "${2:-}" ]]; then
        echo "--owner requires a value" >&2
        exit 2
      fi
      OWNER="$2"
      shift 2
      ;;
    --project)
      if [[ $# -lt 2 || ! "${2:-}" =~ ^[0-9]+$ ]]; then
        echo "--project requires a numeric project number" >&2
        exit 2
      fi
      PROJECT="$2"
      shift 2
      ;;
    --apply)
      APPLY=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

for command in gh jq; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "Required command not found: ${command}" >&2
    exit 1
  fi
done

print_ui_checklist() {
  echo "Saved views (created by this script with --apply):"
  jq -r '.project.views[] | "- \(.name): layout=\(.layout) filter=\(.filter)"' "${MANIFEST}"
  echo "View fields that GitHub currently requires the UI to add:"
  jq -r '.project.uiOnlyViewFields[] | "- \(.)"' "${MANIFEST}"
  echo "Default view cleanup (GitHub UI):"
  echo "- Rename or remove View 1 after verifying the managed views"
  echo "Project workflows (source-controlled provider plus desired transitions):"
  jq -r '.project.workflows | to_entries[] | "- \(.key)=\(.value)"' "${MANIFEST}"
  echo "Manual transitions:"
  jq -r '.project.manualTransitions | to_entries[] | "- \(.key)=\(.value)"' "${MANIFEST}"
}

print_desired_configuration() {
  jq -r --arg owner "${OWNER}" '
    "owner=\($owner)",
    "repository=\(.project.repository)",
    "title=\(.project.title)",
    "visibility=\(.project.visibility)",
    "statuses=\([.project.statuses[].name] | join(","))",
    "priorities=\([.priorities[].name] | join(","))"
  ' "${MANIFEST}"
}

validate_existing_single_select() {
  local field_name="$1"
  local desired_path="$2"
  local allow_missing="$3"
  local matching_fields field_count field desired_names current_names

  matching_fields="$(jq -c --arg name "${field_name}" '(.fields // .) | map(select(.name == $name))' <<<"${fields}")"
  field_count="$(jq 'length' <<<"${matching_fields}")"
  if [[ "${field_count}" -eq 0 ]]; then
    if ${allow_missing}; then
      return
    fi
    echo "Missing Project field: ${field_name}" >&2
    exit 1
  fi
  if [[ "${field_count}" -gt 1 ]]; then
    echo "Multiple Project fields named '${field_name}' exist; refusing to modify the Project." >&2
    exit 1
  fi

  field="$(jq -c '.[0]' <<<"${matching_fields}")"
  desired_names="$(jq -r "${desired_path} | [.[].name] | join(\",\")" "${MANIFEST}")"
  current_names="$(jq -r '[(.options // [])[].name] | join(",")' <<<"${field}")"
  if [[ "${item_count}" -gt 0 && "${desired_names}" != "${current_names}" ]]; then
    echo "Refusing to replace ${field_name} options on a non-empty Project." >&2
    echo "expected: ${desired_names}" >&2
    echo "actual:   ${current_names}" >&2
    exit 1
  fi
}

title="$(jq -r '.project.title' "${MANIFEST}")"
description="$(jq -r '.project.description' "${MANIFEST}")"
readme="$(jq -r '.project.readme' "${MANIFEST}")"
visibility="$(jq -r '.project.visibility' "${MANIFEST}")"

if [[ -z "${PROJECT}" ]]; then
  projects="$(gh project list --owner "${OWNER}" --limit 100 --format json)"
  matches="$(jq -c --arg title "${title}" '[.projects[] | select(.title == $title)]' <<<"${projects}")"
  match_count="$(jq 'length' <<<"${matches}")"
  if [[ "${match_count}" -eq 0 ]]; then
    if ! ${APPLY}; then
      print_desired_configuration
      echo "target_project=<new>"
      print_ui_checklist
      exit 0
    fi
    PROJECT="$(gh project create --owner "${OWNER}" --title "${title}" --format json --jq '.number')"
  elif [[ "${match_count}" -eq 1 ]]; then
    PROJECT="$(jq -r '.[0].number' <<<"${matches}")"
  else
    echo "Multiple projects named '${title}' exist; pass --project explicitly." >&2
    exit 1
  fi
fi

project="$(gh project view "${PROJECT}" --owner "${OWNER}" --format json)"
item_count="$(jq -r '.items.totalCount' <<<"${project}")"
actual_owner="$(jq -r '.owner.login' <<<"${project}")"
actual_title="$(jq -r '.title' <<<"${project}")"
if [[ "${actual_owner}" != "${OWNER}" || "${actual_title}" != "${title}" ]]; then
  echo "Refusing to modify unexpected Project ${OWNER}#${PROJECT}." >&2
  echo "expected: owner=${OWNER} title=${title}" >&2
  echo "actual:   owner=${actual_owner} title=${actual_title}" >&2
  exit 1
fi

linked_repositories="$(gh api graphql \
  -f query='query($owner: String!, $project: Int!) {
    organization(login: $owner) {
      projectV2(number: $project) {
        repositories(first: 100) { nodes { nameWithOwner } }
      }
    }
  }' \
  -f owner="${OWNER}" \
  -F project="${PROJECT}" \
  --jq '.data.organization.projectV2.repositories.nodes[].nameWithOwner')"
unexpected_repositories="$(jq -Rn --arg expected "${REPOSITORY}" \
  '[inputs | select(length > 0 and . != $expected)]' <<<"${linked_repositories}")"
if [[ "$(jq 'length' <<<"${unexpected_repositories}")" -gt 0 ]]; then
  echo "Refusing to modify a Project linked to unexpected repositories." >&2
  jq -r '.[] | "unexpected repository: \(.)"' <<<"${unexpected_repositories}" >&2
  exit 1
fi

fields="$(gh project field-list "${PROJECT}" --owner "${OWNER}" --format json)"
validate_existing_single_select "Status" '.project.statuses' false
validate_existing_single_select "Priority" '.priorities' true

if ! ${APPLY}; then
  print_desired_configuration
  echo "target_project=${OWNER}#${PROJECT}"
  echo "target_url=$(jq -r '.url' <<<"${project}")"
  echo "target_items=${item_count}"
  if grep -Fxq "${REPOSITORY}" <<<"${linked_repositories}"; then
    echo "repository_link=present"
  else
    echo "repository_link=missing"
  fi
  print_ui_checklist
  exit 0
fi

gh project edit "${PROJECT}" \
  --owner "${OWNER}" \
  --title "${title}" \
  --description "${description}" \
  --readme "${readme}" \
  --visibility "${visibility}" \
  --format json >/dev/null

if ! grep -Fxq "${REPOSITORY}" <<<"${linked_repositories}"; then
  gh project link "${PROJECT}" --owner "${OWNER}" --repo "${REPOSITORY}"
fi

priority_field="$(jq -c '(.fields // .) | map(select(.name == "Priority")) | first // empty' <<<"${fields}")"
if [[ -z "${priority_field}" ]]; then
  priority_options="$(jq -r '[.priorities[].name] | join(",")' "${MANIFEST}")"
  gh project field-create "${PROJECT}" \
    --owner "${OWNER}" \
    --name Priority \
    --data-type SINGLE_SELECT \
    --single-select-options "${priority_options}" \
    --format json >/dev/null
  fields="$(gh project field-list "${PROJECT}" --owner "${OWNER}" --format json)"
fi

update_single_select() {
  local field_name="$1"
  local desired_path="$2"
  local field desired desired_names current_names field_id options_literal mutation

  field="$(jq -c --arg name "${field_name}" '(.fields // .) | map(select(.name == $name)) | first // empty' <<<"${fields}")"
  if [[ -z "${field}" ]]; then
    echo "Missing Project field: ${field_name}" >&2
    exit 1
  fi

  desired="$(jq -c "${desired_path}" "${MANIFEST}")"
  desired_names="$(jq -r '[.[].name] | join(",")' <<<"${desired}")"
  current_names="$(jq -r '[(.options // [])[].name] | join(",")' <<<"${field}")"
  if [[ "${item_count}" -gt 0 && "${desired_names}" != "${current_names}" ]]; then
    echo "Refusing to replace ${field_name} options on a non-empty Project." >&2
    echo "expected: ${desired_names}" >&2
    echo "actual:   ${current_names}" >&2
    exit 1
  fi

  field_id="$(jq -r '.id' <<<"${field}")"
  options_literal="$(jq -nr --argjson desired "${desired}" --argjson existing "${field}" '
    [$desired[] as $item |
      (($existing.options // [] | map(select(.name == $item.name)) | first // {}) as $old |
        "{" +
        (if $old.id then "id:" + ($old.id | @json) + "," else "" end) +
        "name:" + ($item.name | @json) + "," +
        "color:" + $item.color + "," +
        "description:" + ($item.description | @json) +
        "}")
    ] | join(",")
  ')"
  mutation="mutation { updateProjectV2Field(input: {fieldId: \"${field_id}\", singleSelectOptions: [${options_literal}]}) { projectV2Field { ... on ProjectV2SingleSelectField { id name } } } }"
  gh api graphql -f query="${mutation}" >/dev/null
}

load_views() {
  gh api graphql \
    -f query='query($owner: String!, $project: Int!) {
      organization(login: $owner) {
        projectV2(number: $project) {
          views(first: 100) {
            nodes {
              name
              number
              layout
              filter
              fields(first: 100) {
                nodes {
                  ... on ProjectV2Field { name }
                  ... on ProjectV2IterationField { name }
                  ... on ProjectV2SingleSelectField { name }
                }
              }
            }
          }
        }
      }
    }' \
    -f owner="${OWNER}" \
    -F project="${PROJECT}"
}

configure_views() {
  local views project_fields desired view_name layout filter visible_fields
  local matches match_count missing_fields field_ids payload ui_only_fields

  views="$(load_views)"
  ui_only_fields="$(jq -c '.project.uiOnlyViewFields // []' "${MANIFEST}")"
  project_fields="$(gh api \
    -H "X-GitHub-Api-Version: ${API_VERSION}" \
    "/orgs/${OWNER}/projectsV2/${PROJECT}/fields")"

  while IFS= read -r desired; do
    view_name="$(jq -r '.name' <<<"${desired}")"
    matches="$(jq -c --arg name "${view_name}" '[.data.organization.projectV2.views.nodes[] | select(.name == $name)]' <<<"${views}")"
    match_count="$(jq 'length' <<<"${matches}")"
    if [[ "${match_count}" -gt 1 ]]; then
      echo "Multiple Project views named '${view_name}' exist; reconcile them in the GitHub UI." >&2
      exit 1
    fi
    if [[ "${match_count}" -eq 1 ]]; then
      continue
    fi

    layout="$(jq -r '.layout' <<<"${desired}")"
    filter="$(jq -r '.filter // ""' <<<"${desired}")"
    visible_fields="$(jq -c '.visibleFields' <<<"${desired}")"
    missing_fields="$(jq -cn \
      --argjson wanted "${visible_fields}" \
      --argjson available "${project_fields}" \
      '$wanted - [$available[].name]')"
    if [[ "$(jq 'length' <<<"${missing_fields}")" -gt 0 ]]; then
      echo "View '${view_name}' references missing fields: $(jq -r 'join(", ")' <<<"${missing_fields}")" >&2
      exit 1
    fi
    field_ids="$(jq -cn \
      --argjson wanted "${visible_fields}" \
      --argjson available "${project_fields}" \
      '[$wanted[] as $name | $available[] | select(.name == $name) | .id]')"
    payload="$(jq -cn \
      --arg name "${view_name}" \
      --arg layout "${layout}" \
      --arg filter "${filter}" \
      --argjson visible_fields "${field_ids}" \
      '{name: $name, layout: $layout, filter: $filter, visible_fields: $visible_fields}')"
    gh api --method POST \
      -H "X-GitHub-Api-Version: ${API_VERSION}" \
      "/orgs/${OWNER}/projectsV2/${PROJECT}/views" \
      --input - <<<"${payload}" >/dev/null
  done < <(jq -c '.project.views[]' "${MANIFEST}")

  views="$(load_views)"
  while IFS= read -r desired; do
    view_name="$(jq -r '.name' <<<"${desired}")"
    matches="$(jq -c --arg name "${view_name}" '[.data.organization.projectV2.views.nodes[] | select(.name == $name)]' <<<"${views}")"
    match_count="$(jq 'length' <<<"${matches}")"
    if [[ "${match_count}" -ne 1 ]]; then
      echo "Project view '${view_name}' was not created exactly once." >&2
      exit 1
    fi
    if ! jq -e --argjson desired "${desired}" --argjson uiOnly "${ui_only_fields}" '
      .[0] as $actual |
      ([$actual.fields.nodes[].name] | unique) as $actualFields |
      ($desired.visibleFields | unique) as $requiredFields |
      (($requiredFields + $uiOnly) | unique) as $allowedFields |
      $actual.layout == (($desired.layout | ascii_upcase) + "_LAYOUT") and
      ($actual.filter // "") == ($desired.filter // "") and
      (($requiredFields - $actualFields) | length) == 0 and
      (($actualFields - $allowedFields) | length) == 0
    ' <<<"${matches}" >/dev/null; then
      echo "Project view '${view_name}' does not match the taxonomy; reconcile it in the GitHub UI." >&2
      exit 1
    fi
  done < <(jq -c '.project.views[]' "${MANIFEST}")
}

update_single_select "Status" '.project.statuses'
update_single_select "Priority" '.priorities'
configure_views

echo "Configured ${OWNER} Project #${PROJECT}: ${title}"
echo "URL: https://github.com/orgs/${OWNER}/projects/${PROJECT}"
print_ui_checklist
