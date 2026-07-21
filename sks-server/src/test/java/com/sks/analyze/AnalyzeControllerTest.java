package com.sks.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AnalyzeController#getTask} 纯单元测试——验证 TOP20 明细加载门控（I1 修复）。
 *
 * <p>不加载 Spring 上下文 / DB——直接 mock {@link AnalyzeService}，断言
 * {@code listBenchmarkVideos} 仅在 {@code account} 任务且 {@code status='done' || 'partial'} 时调用。
 *
 * <p><b>RED（修复前）：</b>原门控 {@code "done".equals(status)} 导致 {@code partial} 账号任务
 * 不加载 TOP20——Python 已为成功条目写了 benchmark_video 行却被隐藏。修复后门控
 * {@code "done".equals(status) || "partial".equals(status)} → GREEN。
 */
class AnalyzeControllerTest {

    private static AnalyzeTask task(String taskType, String status) {
        AnalyzeTask t = new AnalyzeTask();
        t.setId(42L);
        t.setUserId(7L);
        t.setTaskType(taskType);
        t.setStatus(status);
        return t;
    }

    private static BenchmarkVideo video(long id, String title) {
        BenchmarkVideo v = new BenchmarkVideo();
        v.setId(id);
        v.setAnalyzeTaskId(42L);
        v.setTitle(title);
        return v;
    }

    /** partial account → 必须加载 TOP20（I1 核心断言）。修复前此测试 RED。 */
    @Test
    void getTaskLoadsBenchmarkVideosForPartialAccount() {
        AnalyzeService svc = mock(AnalyzeService.class);
        when(svc.getTask(7L, 42L)).thenReturn(task("account", "partial"));
        when(svc.listBenchmarkVideos(42L)).thenReturn(List.of(video(1L, "爆款1")));
        AnalyzeController controller = new AnalyzeController(svc);

        AnalyzeController.TaskDetail detail =
                controller.getTask(7L, 42L).data();

        assertEquals("partial", detail.status());
        assertEquals(1, detail.videos().size());
        assertEquals("爆款1", detail.videos().get(0).title());
        verify(svc).listBenchmarkVideos(42L);
    }

    /** done account → 仍加载 TOP20（回归保护，原行为）。 */
    @Test
    void getTaskLoadsBenchmarkVideosForDoneAccount() {
        AnalyzeService svc = mock(AnalyzeService.class);
        when(svc.getTask(7L, 42L)).thenReturn(task("account", "done"));
        when(svc.listBenchmarkVideos(42L)).thenReturn(List.of(video(1L, "爆款1")));
        AnalyzeController controller = new AnalyzeController(svc);

        AnalyzeController.TaskDetail detail =
                controller.getTask(7L, 42L).data();

        assertEquals("done", detail.status());
        assertEquals(1, detail.videos().size());
        verify(svc).listBenchmarkVideos(42L);
    }

    /** 非 account（video）任务 → 不加载 TOP20（即使 done / partial）。 */
    @Test
    void getTaskDoesNotLoadBenchmarkVideosForVideoTasks() {
        AnalyzeService svc = mock(AnalyzeService.class);
        when(svc.getTask(7L, 42L)).thenReturn(task("video", "done"));
        AnalyzeController controller = new AnalyzeController(svc);

        AnalyzeController.TaskDetail detail =
                controller.getTask(7L, 42L).data();

        assertTrue(detail.videos().isEmpty());
        verify(svc, never()).listBenchmarkVideos(42L);
    }
}
