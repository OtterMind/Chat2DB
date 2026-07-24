#!/usr/bin/env ruby

require 'date'
require 'json'
require 'yaml'

root = File.expand_path('../..', __dir__)
manifest_path = File.join(root, '.github', 'issue-taxonomy.json')
boundaries_path = File.join(root, '.github', 'contribution-boundaries.yml')
operations_path = File.join(root, '.github', 'COMMUNITY_OPERATIONS.md')
maintenance_form_path = File.join(root, '.github', 'ISSUE_TEMPLATE', 'maintenance.yml')

def assert(condition, message)
  raise message unless condition
end

manifest = JSON.parse(File.read(manifest_path))
boundaries = YAML.safe_load(File.read(boundaries_path), aliases: false)
maintenance_form = YAML.safe_load(File.read(maintenance_form_path), aliases: false)
operations = File.read(operations_path)

assert(boundaries['version'] == 1, 'contribution boundaries version must be 1')
assert(boundaries['owner'].is_a?(String) && !boundaries['owner'].empty?, 'boundaries owner is required')
assert(boundaries['areas'].is_a?(Array) && !boundaries['areas'].empty?, 'at least one boundary area is required')

required_keys = %w[id status reason_category boundary alternative owner review_after source]
allowed_statuses = %w[open approval-required closed]
ids = []

boundaries['areas'].each do |area|
  missing = required_keys.reject { |key| area[key].is_a?(String) && !area[key].strip.empty? }
  assert(missing.empty?, "boundary #{area['id'] || '<unknown>'} is missing: #{missing.join(', ')}")
  assert(allowed_statuses.include?(area['status']), "boundary #{area['id']} has an invalid status")
  Date.iso8601(area['review_after'])
  ids << area['id']
end

assert(ids.uniq.length == ids.length, 'boundary ids must be unique')
assert(allowed_statuses.all? { |status| operations.include?("`#{status}`") }, 'operations runbook must explain every boundary status')

labels = manifest.fetch('labels').map { |label| label.fetch('name') }
%w[contribution/good-first-issue contribution/help-wanted].each do |label|
  assert(labels.include?(label), "taxonomy is missing #{label}")
end

statuses = manifest.fetch('project').fetch('statuses').map { |status| status.fetch('name') }
%w[Inbox Backlog Ready In\ Progress In\ Review Done].map { |value| value.tr('\\', '') }.each do |status|
  assert(statuses.include?(status), "project taxonomy is missing #{status}")
end

assert(maintenance_form['type'] == 'Task', 'maintenance form must create Task Issues')
form_ids = maintenance_form.fetch('body').map { |item| item['id'] }.compact
%w[edition category problem scope non-goals acceptance verification contribution checklist].each do |id|
  assert(form_ids.include?(id), "maintenance form is missing #{id}")
end

puts "Validated #{boundaries['areas'].length} contribution boundaries, Project statuses, contributor labels, and the maintenance task form."
