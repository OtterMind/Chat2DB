#!/usr/bin/env ruby
# Validate release routing and version arithmetic without building or signing packages.
require 'minitest/autorun'
require 'open3'
require 'tmpdir'
require 'yaml'

class CommunityBetaWorkflowTest < Minitest::Test
  ROOT = File.expand_path('../../..', __dir__)
  WORKFLOW = YAML.safe_load(File.read(File.join(ROOT, '.github/workflows/jcef_release.yml')))
  RESOLVE = WORKFLOW.fetch('jobs').fetch('resolve')
  BUILD = WORKFLOW.fetch('jobs').fetch('build')
  VERSION_STEP = RESOLVE.fetch('steps').find { |step| step['id'] == 'metadata' }.fetch('run')

  def native(version)
    Open3.capture3('bash', File.join(ROOT, 'script/package/community-version.sh'), version)
  end

  def metadata(version, event: 'workflow_dispatch', ref: 'refs/heads/main', source: 'feature/example', publish_release: 'false')
    Dir.mktmpdir('community-version-') do |dir|
      output = File.join(dir, 'outputs')
      env = {'EVENT_NAME' => event, 'GITHUB_REF' => ref, 'REF_NAME' => ref.split('/').last,
             'INPUT_VERSION' => version, 'SOURCE_REF' => source, 'PUBLISH_RELEASE' => publish_release,
             'GITHUB_OUTPUT' => output}
      stdout, stderr, status = Open3.capture3(env, 'bash', '-c', VERSION_STEP, chdir: ROOT)
      values = File.exist?(output) ? File.readlines(output).map { |line| line.strip.split('=', 2) }.to_h : {}
      [values, status, stdout + stderr]
    end
  end

  def test_native_upgrade_order
    versions = %w[5.3.7-beta.2 5.3.7-beta.3 5.3.7-beta.98 5.3.7 5.3.8-beta.1 5.3.8 5.4.0-beta.1]
    expected = %w[5.3.702 5.3.703 5.3.798 5.3.799 5.3.801 5.3.899 5.4.1]
    actual = versions.map do |version|
      stdout, stderr, status = native(version)
      assert status.success?, stderr
      stdout.strip
    end
    assert_equal expected, actual
    assert_equal actual.map { |v| v.split('.').map(&:to_i) }.sort, actual.map { |v| v.split('.').map(&:to_i) }
  end

  def test_invalid_native_versions_fail_before_packaging
    %w[5.3.7-beta.0 5.3.7-beta.99 5.3.7-beta.100 5.3.7-beta.03 5.3.7-beta.3.extra
       05.3.7 5.03.7 5.3.07 5.3.7-rc.1 5.3.7+local v5.3.7 256.0.0 5.256.0 5.3.656
       5.3.655-beta.36 5.3.655].each do |version|
      stdout, _, status = native(version)
      refute status.success?, version
      assert_empty stdout, version
    end
    stdout, stderr, status = native('255.255.655-beta.35')
    assert status.success?, stderr
    assert_equal '255.255.65535', stdout.strip
  end

  def test_manual_beta_keeps_full_version_and_never_publishes
    values, status, error = metadata('5.3.7-beta.3')
    assert status.success?, error
    assert_equal({'version' => '5.3.7-beta.3', 'native_version' => '5.3.703',
                  'update_channel' => 'BETA', 'tag_name' => 'v5.3.7-beta.3',
                  'channel' => 'beta', 'create_release' => 'false', 'publish' => 'false',
                  'prerelease' => 'true', 'latest' => 'false'}, values)
  end

  def test_manual_beta_release_is_explicitly_opt_in
    values, status, error = metadata('5.3.7-beta.3', publish_release: 'true')
    assert status.success?, error
    assert_equal 'true', values['create_release']
  end

  def test_manual_requires_main_source_ref_and_beta_version
    [metadata('5.3.7-beta.3', ref: 'refs/heads/feature'),
     metadata('5.3.7-beta.3', source: ''), metadata('5.3.7-beta.3', source: '  '),
     metadata('5.3.7'), metadata('3.9.0-beta.1')].each do |values, status, _|
      refute status.success?
      assert_empty values
    end
  end

  def test_stable_tag_retains_formal_release_route
    values, status, error = metadata('', event: 'push', ref: 'refs/tags/v5.3.7', source: '')
    assert status.success?, error
    assert_equal 'true', values['publish']
    assert_equal 'true', values['create_release']
    assert_equal 'false', values['prerelease']
    assert_equal 'true', values['latest']
    assert_equal 'release', values['channel']
    assert_equal 'STABLE', values['update_channel']
    assert_equal '5.3.799', values['native_version']
  end

  def test_beta_tag_cannot_reach_stable_publish
    values, status, _ = metadata('', event: 'push', ref: 'refs/tags/v5.3.7-beta.3')
    refute status.success?
    assert_empty values
  end

  def test_source_is_resolved_once_and_helpers_stay_on_workflow_commit
    source = RESOLVE['steps'].find { |step| step['name'] == 'Resolve source checkout' }
    assert_includes source['with']['ref'], 'inputs.source_ref'
    assert_equal false, source['with']['persist-credentials']
    assert_equal '${{ needs.resolve.outputs.build_sha }}', BUILD['steps'][0]['with']['ref']
    helpers = BUILD['steps'].find { |step| step['name'] == 'Check out workflow packaging helpers' }
    assert_equal '${{ github.sha }}', helpers['with']['ref']
    assert_equal false, helpers['with']['persist-credentials']
    commands = BUILD['steps'].map { |step| step['run'] }.compact.join("\n")
    assert_includes commands, '.community-packaging/script/package/package-community-jcef.sh'
    assert_includes commands, '.community-packaging/script/package/prepare_community_update.sh'
  end

  def test_native_executables_are_uploaded_and_release_jobs_are_gated
    upload = BUILD['steps'].find { |step| step['name'] == 'Upload platform artifacts to GitHub Actions' }
    assert_includes upload['with']['path'], 'jpackage/output/*.exe'
    assert_includes upload['with']['path'], 'jpackage/output/build-provenance.json'
    assert_equal "${{ needs.resolve.outputs.create_release == 'true' }}", WORKFLOW['jobs']['stage_release']['if']
    assert_includes WORKFLOW['jobs']['publish_release']['if'], 'needs.resolve.outputs.channel == \'beta\''
    assert_equal "${{ needs.resolve.outputs.publish == 'true' }}", WORKFLOW['jobs']['docker']['if']
    assert_equal "${{ needs.resolve.outputs.publish == 'true' }}", WORKFLOW['jobs']['docker']['if']
    assert_includes WORKFLOW['jobs']['publish_release']['if'], 'always()'
    assert_includes WORKFLOW['jobs']['publish_release']['if'], "needs.stage_release.result == 'success'"
    %w[stage_release publish_release].each do |name|
      refute_equal "${{ needs.resolve.outputs.publish == 'true' }}", WORKFLOW['jobs'][name]['if']
    end
  end

  def test_beta_index_is_published_only_after_the_version_release
    publish = WORKFLOW['jobs']['publish_release']
    steps = publish['steps']
    release = steps.index { |step| step['name'] == 'Publish validated Release' }
    index = steps.index { |step| step['name'] == 'Publish Beta update index' }
    assert_operator index, :>, release
    assert_equal "${{ needs.resolve.outputs.channel == 'beta' }}", steps[index]['if']
    assert_equal false, publish['concurrency']['cancel-in-progress']
    assert_includes publish['concurrency']['group'], 'needs.resolve.outputs.channel'
  end

  def test_publish_release_is_a_boolean_dispatch_input
    input = WORKFLOW.fetch(true).fetch('workflow_dispatch').fetch('inputs').fetch('publish_release')
    assert_equal true, input['required']
    assert_equal 'boolean', input['type']
    assert_equal false, input['default']
  end
end
