package com.lirui.oneline.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §84 雨课堂「作业 / 小测 / 考试」按标题分类的测试。
 *
 * ★ 背景（用户提供的关键信息）：
 *   **雨课堂只有一个「考试」发布入口** —— 老师发的平时作业、小测、期末考试
 *   全都以 `type=5` / `leaf_type=5` 出现。所以「到底是什么类型」
 *   接口字段里根本没有，**只能靠标题命名判断**。
 *
 * 下面每一条样本都取自 2026-11-16 真机抓到的 49 条实际标题。
 */
class YuketangKindTest {

    private fun kind(t: String) = NativeProbeRunner.classifyYuketangKind(t)

    @Test
    fun `weekly homework is homework`() {
        assertEquals("作业", kind("第16周作业"))
        assertEquals("作业", kind("第9周作业"))
        assertEquals("作业", kind("Homework_7"))
        assertEquals("作业", kind("第九讲作业"))
        assertEquals("作业", kind("第七讲\"傅里叶变换\"作业"))
        assertEquals("作业", kind("第一讲信号与系统基础（上）作业"))
    }

    @Test
    fun `quiz words map to quiz`() {
        assertEquals("小测", kind("小测1"))
        assertEquals("小测", kind("小测3"))
        assertEquals("小测", kind("Quiz 7"))
        assertEquals("小测", kind("第1、2章小测验"))
        assertEquals("小测", kind("傅里叶级数随堂小测（1）"))
    }

    @Test
    fun `assessment words map to assess`() {
        // 通识课常用「考查/考察」，实质是期末作业。
        assertEquals("考查", kind("劳动教育考查-2025-2026春"))
        assertEquals("考查", kind("国家安全教育考察-25-26秋"))
        assertEquals("考查", kind("劳动教育考察25-26秋（L）"))
    }

    @Test
    fun `real exam words map to exam`() {
        assertEquals("考试", kind("期中考试"))
        assertEquals("考试", kind("期末考试"))
        assertEquals("考试", kind("Final Exam"))
    }

    @Test
    fun `report with final keyword is homework not exam`() {
        // ★ 真机里的坑：「电路实习期末报告提交」同时含「期末」与「报告」。
        //   它本质上是一份报告作业，不该被标成"考试"吓人。
        assertEquals("作业", kind("电路实习期末报告提交"))
    }

    @Test
    fun `practice and exercises default to homework`() {
        assertEquals("作业", kind("练习"))
        assertEquals("作业", kind("积分填空"))
        assertEquals("作业", kind("积分选择"))
        // 无任何关键词，也默认作业（安全侧：不误标成考试）。
        assertEquals("作业", kind("第五讲傅里叶级数（1）"))
        assertEquals("作业", kind("第四讲因果的LTI系统，微分方程和差分方程"))
        assertEquals("作业", kind(""))
    }

    @Test
    fun `combined homework and exam words prefer exam`() {
        // 同时含「作业」与「考试」时按考试判（更醒目，不会漏看）。
        assertEquals("考试", kind("作业考试"))
    }

    @Test
    fun `deadline formatter handles zero and valid`() {
        assertEquals("无截止", NativeProbeRunner.fmtMs(0L))
        // 1795309200000 = 2026-11-22 09:00 Asia/Shanghai
        assertEquals("11-22 09:00", NativeProbeRunner.fmtMs(1795309200000L))
    }
}
