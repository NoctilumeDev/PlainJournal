# 遗留物收口门禁

遗留物收口发生在实现、测试、验收和文档读回之后，里程碑或发布关闭之前。它不证明
业务正确性，只阻止已经失去职责的施工产物继续进入下一阶段。

## 所有权边界

| 状态 | 判据 | 处置 |
| --- | --- | --- |
| KEEP | 当前产品、文档、正式发布或终局 Evidence 仍消费 | 留在仓库并由消费方维护 |
| ARCHIVE | 仍有明确历史职责，但不代表当前产品状态 | 保留最小历史材料并声明坐标 |
| EXTERNALIZE | 需要长期保存，但不适合每次 checkout 携带 | 交给 Release、Git 历史或专用存储 |
| DELETE | 可重建、无活跃引用且没有证明责任 | 从当前主线删除，删除历史由 Git 保存 |
| REVIEW | 当前无法确认 owner 或 consumer | 停止自动处置，先人工确认 |

文件大小只触发审计，不直接决定处置。历史上存在过，也不自动产生永久保留资格。

## 仓库门禁

`node tools/check-residual-hygiene.mjs` 是只读检查器，只检查 Git 跟踪状态：

- 拒绝已被 `.gitignore` 定义为本地产物、却仍被 Git 跟踪的文件；
- 拒绝 `history/worklogs/` 下的历史施工图片、视频、PDF 等媒体继续进入当前 checkout；
- 拒绝 `.tmp`、`.bak`、`.orig`、`.rej` 与编辑器备份文件；
- 不按仓库大小、图片数量或固定 KEEP 数量建立 KPI；
- 不删除、移动、格式化或重写任何被检查文件。

当前产品截图继续由 `docs/assets/` 和在线预览消费；正式终局验收快照继续由
`docs/evidence/` 及其既有结构门禁负责。历史设计 QA 中需要回看的施工图，链接到删除前
的精确 Git 提交，不再复制到当前工作树。

## 本地休眠不是 CI 事实

`node_modules/`、`target/`、Coverage、浏览器输出、Docker 容器、镜像、Volume 和本地
数据库属于工作区或宿主运行状态，GitHub CI 无法证明用户机器已经清理。项目长期休眠前，
应先确认不存在唯一的本地代码、数据库或对象存储事实，再使用项目自己的停止与清理入口。
不以强制删除、Factory Reset 或跨项目批量清理代替所有权确认。

## 当前状态

```text
Residual Hygiene: PASS

tracked ignored artifacts       0
historical construction assets  0
tracked temporary files         0
```

这些数字是当前门禁结果，不是必须永久保持相等的产品指标。详细删除记录由对应 Git diff
保存，不再额外生成清理截图、哈希清单或第二套 qualification。
