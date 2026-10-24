undefined
# Preserve the renderer's screenshot/clearance regression test when rebuilding the integration branch.
render_test = root/'_integration/GwmAndroidAutoClusterRenderTest.kt'
require(render_test.is_file(), 'Missing Android Auto cluster rendering test')
(root/'common/src/test/java/com/shilapi/xcertplay/GwmAndroidAutoClusterRenderTest.kt').write_text(render_test.read_text())
