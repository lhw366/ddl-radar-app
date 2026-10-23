# Git 与 GitHub：从入门到进阶实战

学生讲义 · 含教师指导、实验、参考答案与评分标准

修订日期：2026-10-23。适用：有基本文件操作能力、没有版本控制基础的学生。主线环境：Windows PowerShell、Git 2.23 及以上、GitHub；macOS / Linux 可按说明替换文件创建命令。GitHub 界面与权限因账号、组织和套餐不同而变化，以课堂当天官方文档为准。

## 如何使用本教材

本课程用“课程提醒器”这个小项目串起个人开发、双人协作、自动检查与发布。前四课可离线完成；第五课起需要 GitHub 账号。建议 12 次课，每次 90–120 分钟，另留 6–8 小时做结课项目。第一次学习不必记住所有命令，先做到能解释“我正在改哪个分支、改了什么、哪些内容会被提交、提交到了哪里”。

课程结束的目标是独立完成一次有证据的交付：建立仓库、拆分提交、处理冲突、通过 PR 审查与 CI、发布可追溯版本，并能从误操作中恢复。熟练程度要靠后续项目积累；做完讲义并不意味着无需再查文档。

| 阶段 | 课程 | 可以展示的成果 |
| --- | --- | --- |
| 个人版本控制 | 1–4 | 本地仓库、清晰提交、分支合并、一次安全恢复 |
| 团队协作 | 5–8 | 可审查 PR、冲突处理、历史整理、自动检查 |
| 交付与排障 | 9–12 | 版本发布、故障定位、权限设计、团队项目 |

所有示例都在新建练习目录中执行，不在学校共享盘或真实生产仓库上试验。命令块不含 `$` 或 `PS>` 提示符，可以逐行运行；尖括号占位符应先换成实际值，不能直接照抄。不要在课堂截图里展示密码、token、SSH 私钥或学生个人数据。

## 第 1 课：看懂版本控制的四个位置

Git 是分布式版本控制工具；GitHub 是承载仓库、讨论、审查和自动化的协作平台。没有网络也可以用 Git 提交和查看历史。有 GitHub 账号不会自动给电脑安装 Git；把文件上传到网页也不会替你建立规范的本地工作流。

工作区是正在编辑的文件，暂存区是准备进入下一次提交的内容，本地仓库保存已经提交的快照，远程仓库保存推送到服务器的历史。`add` 选择下一次提交的内容，`commit` 在本地记录快照，`push` 把相关提交发送到远端。`fetch` 下载远端引用，通常不改变当前工作区。`pull` 会在获取后进一步整合历史，因此不要把它当成“只看看服务器”。

```text
编辑文件 → 工作区 ── git add ──→ 暂存区 ── git commit ──→ 本地仓库
                                                       │
                                                       └─ git push → GitHub
本地仓库中的 origin/main ← git fetch ← GitHub 上的 main
```

提交不仅包含差异，还关联一棵文件树、父提交、作者、时间和说明。分支是指向提交的可移动名称，`HEAD` 通常指向当前分支。提交哈希用于识别对象，截图里的短哈希只在特定仓库中足够唯一，不应把某个示例哈希用于自己的仓库。

**课堂实验。** 在开始第二课前，画出“改了文件却忘了 add”“add 后又改了一次”“commit 后没 push”分别影响哪个位置。教师随机给出状态，让学生说出下一步应该检查什么。

**验收。** 学生能解释：`commit` 成功不代表同学已经能看到；删除远端分支通常也不会直接删除电脑工作区。

## 第 2 课：安装、身份与第一次提交

从 [Git 官方网站](https://git-scm.com/downloads) 安装 Git，然后重新打开终端。配置作者姓名和邮箱只影响提交署名，不等于登录 GitHub。邮箱可能公开；学生可选择 GitHub 提供的隐私邮箱。公用机优先使用仓库级配置，避免改动下一位同学的全局身份。

```powershell
git --version
New-Item -ItemType Directory course-radar-lab
Set-Location course-radar-lab
git init -b main
git config user.name "Student Example"
git config user.email "student@example.invalid"
Set-Content README.md "# Course Radar" -Encoding utf8
git status
git add README.md
git diff --cached
git commit -m "docs: introduce course radar"
git log --oneline
```

`git init -b main` 需要支持该选项的 Git 版本；较早版本可先 `git init`，再 `git branch -M main`。macOS / Linux 创建目录和文件可使用 `mkdir`、`cd` 和文本编辑器，Git 命令相同。PowerShell 中写中文文件时建议明确使用 UTF-8，统一换行策略在第十一课处理。

**预期结果。** 首次提交后 `git status` 显示工作区干净，`git log --oneline` 至少有一条提交。哈希和时间与同学不同是正常现象。

**练习。** 给 README 添加项目用途和“运行方式”两段，分别提交。使用 `git show HEAD` 查看最近一次提交，说明它与 `git status` 的区别。

**常见问题。** `Author identity unknown` 先检查姓名邮箱配置；`not a git repository` 先看当前位置，不要为了消除错误就在任意目录 `git init`；提示编辑提交说明时，可保存退出编辑器，或初学阶段用 `-m` 提供简短说明。

## 第 3 课：有选择地提交，而不是保存全部文件

一个提交应完成一个能说明的问题。修复提醒同步与调整全站配色通常不应混在同一个提交。好的提交说明描述结果或目的，例如“fix: avoid duplicate overdue reminders”；“update”“111”“最终版”难以支持后续审查。

```powershell
git status --short
git diff
git add README.md
git diff --cached
git commit -m "docs: explain local verification"
```

`git diff` 默认比较工作区与暂存区；`git diff --cached` 比较暂存区与当前提交。文件 add 后再编辑，新编辑的部分仍在工作区，需要再次 add 才会进入本次提交。先检查 diff，再提交，能发现误传文件、拼写错误及混入的无关修改。

使用 `.gitignore` 排除本地生成物，例如 `node_modules/`、构建缓存和个人配置。它只影响未跟踪文件的常规添加行为，不能让已经跟踪的秘密自动从历史中消失。确需停止跟踪文件时，先理解 `git rm --cached` 只从索引删除、保留工作区文件，再单独审查提交；它同样不会清除旧历史。

```text
node_modules/
runtime-evidence/
*.log
.env
```

**实验。** 新建 `notes.txt`，写第一行并 add；再写第二行，运行上述两个 diff。提交后检查哪一行进入历史。随后使用 `git add -p notes.txt` 体验按片段暂存，解释为何它适合拆分提交。

**验收。** 提交中不包含构建缓存和私人配置；作者能逐条说明暂存差异，不仅回答“我点了保存”。

## 第 4 课：分支、合并与可重复的冲突实验

从干净状态建立功能分支。建立分支不会复制一套完整文件，切换分支会让工作区呈现该分支对应的内容。未提交修改能否随切换保留，取决于是否与目标分支冲突；不要以“切分支一定安全”替代检查。

在当前练习仓库中新建 `message.txt`，提交后按下列步骤制造可控冲突。`Set-Content` 会覆盖这个练习文件，只在本实验目录使用。

```powershell
Set-Content message.txt "Reminder: original" -Encoding utf8
git add message.txt
git commit -m "feat: add reminder message"
git switch -c feature/banner
Set-Content message.txt "Reminder: top banner" -Encoding utf8
git add message.txt
git commit -m "feat: describe top banner"
git switch main
Set-Content message.txt "Reminder: system notification" -Encoding utf8
git add message.txt
git commit -m "feat: describe system notification"
git merge feature/banner
```

**预期结果。** Git 报告内容冲突，文件出现 `<<<<<<<`、`=======`、`>>>>>>>`。不要盲目选择 ours 或 theirs。先看两侧业务意图，再编辑成双方认可的最终文本，例如 `Reminder: system notification with top banner`，删除冲突标记，然后完成合并。

```powershell
git status
git diff
git add message.txt
git commit -m "merge: combine notification and banner wording"
git log --graph --oneline --all
```

如果准备放弃这次合并，可在仍处于合并状态时使用 `git merge --abort`。它不是“删除所有分支”，也不能保证修复合并前已经存在的任意脏工作区；实验开始前保持干净。

**验收。** 同学能指出分叉点和合并提交的两个父方向，最终文件没有冲突标记。没有冲突的 merge 也必须做行为检查：两个文件各自合法，组合后仍可能出现业务错误。

## 第 5 课：GitHub、远端与身份认证

先在 GitHub 建立空练习仓库。如果要推送已有本地仓库，创建时不要同时生成另一个 README，以免初学阶段立即出现两段独立历史。仓库是公开还是私有由课堂需求决定；公开仓库不是存放学生个人作业隐私的默认选择。

HTTPS 推荐使用受支持的凭据管理器或 GitHub CLI 登录；GitHub 账号密码不能直接充当常规 Git HTTPS 推送密码。使用 token 时限制仓库范围、权限与有效期，不把 token 写进 remote URL。SSH 使用公钥放到 GitHub、私钥留在自己的电脑；先确认目标文件不存在，不要覆盖已有密钥。

```powershell
git remote add origin https://github.com/YOUR_ACCOUNT/course-radar-lab.git
git remote -v
git push -u origin main
git branch -vv
```

这里 `origin` 是约定俗成的本地远端名称，不是 GitHub 的保留账号。`-u` 设置当前分支的上游，之后通常可直接 `git push`。`origin/main` 是本地记录的远端跟踪引用，并不会在同学 push 的瞬间自动刷新，需要 fetch。

```powershell
git fetch origin
git log --oneline --left-right main...origin/main
git pull --ff-only
```

`--ff-only` 只接受快进；若本地和远端已经分叉，会停止让你决定 merge 或 rebase，避免无意生成合并。推送被拒绝时先 fetch 检查差异，不以强制推送作为第一反应。`clone` 通常用于首次获得一个已有仓库；`pull` 用于已有工作副本，重复 clone 到嵌套目录会增加混乱。

**双人实验。** B 克隆 A 的练习仓库；A 推送一条提交后，B 在 fetch 前后分别观察 `origin/main`。记录两次哈希变化，解释为什么网页更新了但本地还没有。

官方延伸：[GitHub 身份认证](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/about-authentication-to-github)、[SSH](https://docs.github.com/en/authentication/connecting-to-github-with-ssh/about-ssh)。

## 第 6 课：Issue → 分支 → Pull Request → 审查

Issue 用来描述可讨论的问题，PR 用来提出可审查的具体改动。PR 不只是“请求老师点合并”，还应提供复现步骤、修改后的行为、验证证据和仍未验证的范围。

一个可用的 Issue 示例是：“正常任务在后台到达五分钟前提醒点时无提示；设备型号、安装 build、通知权限、任务设置和实际时间如下”。“bug 满满”表达了体验，但不足以定位；开发者要把体验转化为可以重复的实验，而不是要求使用者先懂实现。

```powershell
git switch main
git pull --ff-only
git switch -c fix/normal-reminder
git status
git add README.md
git commit -m "docs: specify background reminder acceptance"
git push -u origin fix/normal-reminder
```

在 GitHub 打开 PR，base 选择 main，compare 选择功能分支。若没有目标仓库写权限，先 fork，再从自己的 fork 发 PR；fork 是 GitHub 上的仓库副本，clone 是下载工作副本，两者不是同义词。多人共享仓库与 fork 协作的权限模型也不同。

**PR 描述模板。** 用三段写清：具体问题和触发；修复后行为；执行过的验证及限制。不要写“已全面修复”却只列一个五秒测试。审查者先看总体设计和真实入口，再看边界、错误处理和测试，不把格式偏好置于真实故障之前。

**审查练习。** A 提交一个 PR，故意只修改提示文案而没有修复逻辑；B 写一条可行动评论，指出遗漏的具体路径并提出验收方式。A 修复后追加提交，回复检查结果。教师观察讨论是否围绕证据，而不是仅回复“已改”。

**验收。** 一条 Issue、一个关联 PR、一条有实质内容的审查意见、通过的检查和合并记录。团队约定 main 禁止直接推送，可配置规则要求 PR 与检查通过；具体可用规则取决于仓库设置和套餐。

## 第 7 课：撤销与恢复，先判断改动在哪里

恢复操作之前先保存 `git status`、`git diff` 和最近日志。先判断要保留文件内容、撤销暂存、撤销提交，还是撤销已经共享的变更。名称相近的 restore、reset、revert 并不等价。

| 场景 | 优先方法 | 会发生什么 |
| --- | --- | --- |
| add 了不该暂存的文件 | `git restore --staged notes.txt` | 取消暂存，保留工作区内容 |
| 想丢弃某文件未暂存改动 | `git restore notes.txt` | 用索引内容覆盖工作区该文件；会丢失对应未保存改动 |
| 最近一次提交漏了文件，尚未共享 | add 后 `git commit --amend` | 替换最近提交，哈希改变 |
| 已共享的提交有错 | `git revert COMMIT_ID` | 生成反向提交，不抹掉原历史 |
| 临时切换任务但修改未完成 | `git stash push -u -m "wip: draft"` | 保存包括未跟踪文件的临时状态，不包含所有被忽略文件 |
| 误改分支指针，找旧提交 | `git reflog` 后建立恢复分支 | 先把找到的提交重新命名保存 |

`git reset --hard` 会重置索引和工作区，可能丢失未提交修改；`git clean` 会删除未跟踪文件。它们不属于本课默认修复方案。提交过的内容往往还有恢复机会，但 reflog 有保留期限，未提交文件也不一定能找回；Git 不能代替独立备份。

**恢复实验。** 在练习仓库提交 `notes.txt`，用 `git commit --amend` 改提交说明。然后运行以下只读检查，从 reflog 中手工找到修改前提交，先建立恢复分支，不移动现有分支。

```powershell
git reflog --date=local
git show OLD_COMMIT_ID
git branch rescue/before-amend OLD_COMMIT_ID
git log --oneline --all --decorate
```

把 `OLD_COMMIT_ID` 替换成刚才实际找到的哈希。`stash apply` 会尝试恢复但保留 stash；`stash pop` 在成功应用后还会移除记录，发生冲突则需要检查状态，不要连续多次 pop。stash 是本地临时工具，不应成为长期共享作业的方式。

参考：[Git 撤销操作](https://git-scm.com/book/en/v2/Git-Basics-Undoing-Things.html)。

## 第 8 课：写第一个 GitHub Actions workflow

workflow 是仓库里的 YAML 文件，由事件触发，包含一个或多个 job。job 通常在独立 runner 中执行，step 在同一 job 的环境中顺序运行；不同 job 共享文件需要 artifact 等明确传递方式。缓存帮助减少重复下载，不是对外交付的版本包。

创建 `src/check.mjs`，让它检查公开函数的边界，而不是验证“某个变量等于它刚被赋的值”。最小示例可以先检查提醒时间计算：

```javascript
import assert from 'node:assert/strict';
const reminderAt = (due, minutes) => due - minutes * 60000;
assert.equal(reminderAt(600000, 5), 300000);
assert.equal(reminderAt(600000, 0), 600000);
console.log('reminder timing checks passed');
```

先本地运行 `node src/check.mjs`，再创建 `.github/workflows/check.yml`：

```yaml
name: Check reminders
on: [push, pull_request]
permissions:
  contents: read
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      - name: Verify reminder timing
        run: node src/check.mjs
```

教学示例使用可读的版本标签，正式项目应审查 action 来源并考虑固定完整提交 SHA。不要从陌生网页照抄会执行远端脚本的 workflow。失败时先展开失败 step，找最早与本次问题有关的错误，不能只看最后一行 `exit code 1`。

**实验。** 把预期值故意写错，提交后观察红色检查；再修正并记录两个 run 链接。解释为什么“workflow 成功”只证明其中执行过的检查成功。一个仅检查时间减法的 job 无法证明 Android 后台有铃声。

**进阶。** 给构建步骤设置超时，失败也上传日志；只给需要发布的 job 写权限。不要在处理不可信 PR 代码的高权限上下文注入发布密钥。不要为了取到 secrets 就把普通 PR 测试随意改成 `pull_request_target`。

官方延伸：[Workflow 语法](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)、[Actions 安全使用](https://docs.github.com/en/actions/reference/security/secure-use)。

## 第 9 课：版本、标签、Artifact 与 Release

分支名描述开发方向，tag 常用于固定某个版本位置，Release 是平台上的发布记录和附件。Artifact 通常保留某次 CI 运行的产物并有保留期限，不能默认视为永久发布地址。版本号说明兼容性，安装构建号用于区分安装包；它们可以有不同用途。

语义化版本通常按 `主版本.次版本.修订版本` 表示不兼容变化、新功能和修复。但团队必须先定义对外接口和兼容承诺，不能仅按“改了多少行”加号。Android `versionCode` 还需要满足覆盖安装的版本顺序，重新建立 workflow 让计数从一开始，可能破坏更新。

```powershell
git switch main
git pull --ff-only
git status
git tag -a v1.0.0 -m "First classroom release"
git show v1.0.0
git push origin v1.0.0
```

课堂第一次创建 tag 前，先检查该名称不存在。已经对外发布的 tag 不随意移动；需要修复就发布新版本。实际团队可以由 CI 生成 tag，关键是能追溯到确定的源提交、构建环境和验收结果。

**发布说明练习。** 写清修复的问题、用户现在能做什么、怎么更新、验证环境和已知限制。提醒器示例必须说明“模拟器验证后台派发”和“目标 OPPO 真机扬声器与电池测量”之间的差别。不要把下载动作写成静默安装，系统安装确认仍由用户决定。

**验收。** Release 链接能找到正确附件；附件中显示的版本与源提交一致；同学能从发布包反查 tag 和 CI run。失败检查不能被 `continue-on-error` 或无条件发布绕过。

## 第 10 课：进阶历史管理与故障定位

merge 保留分支相遇的历史结构；rebase 把一组提交重放到新基线，通常产生新哈希；squash 把多次开发提交压成较少提交，便于按功能撤销。三者是不同的审查与追溯取舍，没有适用于所有团队的唯一答案。

在自己的未共享功能分支上，可先更新远端信息再 rebase。开始前保持工作区干净，并建立备份分支。发生冲突时解决文件、add，然后继续；想取消整个过程用 abort。

```powershell
git fetch origin
git branch backup/before-rebase
git rebase origin/main
git status
git rebase --continue
```

上面最后一条只在确实处于待继续的 rebase 状态时执行，不能无条件整段运行。取消使用 `git rebase --abort`。不要在未沟通的共享 main 上改写历史。自己的 PR 分支若重写后必须更新远端，应先理解并核对 `--force-with-lease` 的保护条件；它比盲目 force 有保护，但不是所有并发修改的绝对保险。

`cherry-pick` 适合把明确的修复提交应用到另一维护分支，也会产生新的提交；依赖没选全仍可能构建失败。`git blame` 帮助定位某行最近来源，不能据此判定责任，更不代表该行最初设计者就是缺陷原因。

**二分定位实验。** 教师准备 8 个线性提交，第五个开始让 `node src/check.mjs` 失败。学生先确认好坏端点，再使用 bisect。在每次选出的提交上运行检查，按结果标记 good 或 bad，结束后退出二分状态。

```powershell
git bisect start
git bisect bad BAD_COMMIT_ID
git bisect good GOOD_COMMIT_ID
node src/check.mjs
git bisect bad
```

如果检查通过，最后一行应使用 `git bisect good`；重复直到找到首个坏提交，最后 `git bisect reset`。自动化时可以使用 `git bisect run node src/check.mjs`，但退出码必须可靠，环境故障不能当作产品失败。非单调的偶发故障需要先稳定复现，不能机械二分。

参考：[git bisect](https://git-scm.com/docs/git-bisect)、[Git 用户手册](https://git-scm.com/docs/user-manual)。

## 第 11 课：仓库工程化、权限和协作约定

一个能交接的仓库至少需要：说明如何运行的 README、明确源码入口、依赖锁文件、测试命令、贡献流程和版本发布规则。README 写了但从未有人照着跑过，不算完成。让另一组从空目录 clone 并按文档启动，是检验交接质量的有效方法。

| 内容 | 作用 | 提醒器中的例子 |
| --- | --- | --- |
| README | 新成员入口 | 安装、检查、源码目录 |
| 架构说明 | 解释职责和边界 | Web 保存到原生闹钟的链路 |
| CONTRIBUTING | 约定提交和审查 | 修复必须验证真实路径 |
| 锁文件 | 锁定依赖解析结果 | CI 使用 npm ci |
| workflow | 自动执行检查 | 验证通过后才允许发布 |
| 测试夹具 | 可重复复现 | 真实课表图片及其期望课程 |

换行差异会淹没有效改动。团队可用 `.gitattributes` 指定文本换行，例如 `*.js text eol=lf`；先评估已有文件，避免在修 bug 的 PR 里同时全仓库换行。`git diff --stat` 能发现意外大面积格式变化。

仓库大文件会持续增加克隆成本。安装包通常适合 Release 附件或专门分发存储；Git LFS 是否适合取决于配额、工具和访问环境。旧应用若依赖仓库内 APK 的地址更新，不能为了“目录整洁”直接删除，必须设计迁移通道。

权限设计从最小范围开始：读代码不应需要写仓库权限，验证 PR 不应需要发布密钥。使用 rulesets 或保护分支要求审查与检查；可用 CODEOWNERS 指定敏感目录的审查人员。GitHub 规则不能自动证明测试质量，需要教师和维护者共同审查。

秘密意外提交后，先撤销或轮换秘密，再处理历史与传播副本；仅删除最新文件或把仓库改私有不能使已泄露凭据失效。历史清理会改写哈希并影响所有协作者，应单独制定操作方案，不在本课对真实仓库演示。

## 第 12 课：结课项目——把提醒器交给另一个小组

两至四人一组，完成“课程提醒器”改动。可以用纯 JavaScript 模拟提醒计划，不要求每位学生都有 Android 手机。必须区分计划计算与设备效果；没有设备权限时如实标记未验证范围。

**需求。** 任务包含截止时间、可选择的提前 120 / 60 / 30 / 5 分钟提醒和到点提醒；能导入一组课程名称、星期、节次和教室；发现新版时提示用户选择。实现中任选其中一项做完整闭环，其余写清接口和后续范围，不用空壳按钮冒充完成。

**交付顺序。** 先建立 Issue 与验收标准，再开分支；实现和本地验证后发 PR，交由另一组审查；修复审查意见并通过 CI，再合并、打版本和发布。最后给接手组一份五分钟上手说明，让他们从新目录复现。

**提醒验收例。** 设截止时间为 10:00，则四个梯度分别为 08:00、09:00、09:30、09:55，到点为 10:00。关闭 30 分钟梯度只影响 09:30；完成任务后不再排定。新建任务时已经过去的梯度不应集中补响；返回应用时已由原生投递的同一事件不应再次震动。

**课表验收例。** `课程名：线性代数A`、`教室：理教408` 是两个字段，教室不能成为第二门课。重复的连续节次需要合并；备注里的习题课时间不能悄悄改变常规课程的星期。导入取消不修改已有课程；同一数据重复导入不重复创建。

**更新验收例。** 新版本号高于本地才提示；断网不显示“已是最新”的确定结论；用户没有确认前不下载或安装；自动检查限频且不通过后台常驻轮询实现。

| 评分维度 | 分值 | 满分证据 |
| --- | --- | --- |
| Git 基础与提交质量 | 20 | 合理提交、清楚 diff、无无关生成物 |
| 协作与 PR | 20 | 可复现 Issue、实质审查、意见闭环 |
| 实现与检查 | 25 | 真实入口、边界、失败重试、可靠 CI |
| 交付与追溯 | 15 | 版本、源提交、构建与附件一致 |
| 文档与接手验证 | 10 | 另一组按文档能运行 |
| 安全与恢复 | 10 | 最小权限、无秘密、一次恢复演示 |

不能运行的交付必须明确标记，不用漂亮截图抵消缺失行为。没有协作证据的个人项目不在“协作”部分拿满分；没有真机就不因缺手机扣设备测量分，但不得宣称测过。

## 教师指导与参考答案

**课前准备。** 提前检查 Git、Node 和网络；准备一个教师演示仓库与一个可离线克隆的练习仓库。学生账号开启适当的账号保护。投屏前隐藏凭据；不要让全班共享同一个 token。使用一份教师控制的坏历史做第十课，避免学生为了 bisect 先破坏真实作业。

**90 分钟节奏。** 概念讲解 15 分钟，教师演示 15 分钟，双人实验 35 分钟，互查 15 分钟，复盘与提交证据 10 分钟。第一课与第十二课可增加讨论时间。基础薄弱班把第 4、7、8 课拆成两次；进阶班增加 worktree、签名提交、依赖更新和发布回滚设计。

**第 1–3 课答案要点。** add 后再改文件，暂存区仍是 add 时的内容；commit 只保存暂存内容，push 才涉及远端。`git diff` 与 `git diff --cached` 不同，空 diff 不一定意味着工作区所有内容都已提交。`.gitignore` 不清理已经跟踪的历史。

**第 4–6 课答案要点。** 冲突表示 Git 不能自动决定重叠修改结果，不表示谁“做错了”。最终文本要保留业务意图；PR 需要检查组合行为。`origin/main` 需 fetch 才更新；没有写权限使用 fork 后 PR，不要求学生借用教师账号。

**第 7 课答案要点。** 撤销暂存用 restore staged；撤销已共享提交优先 revert；amend 和 rebase 改变提交身份。恢复先建立 rescue 分支，保住找到的对象。未提交内容并无必然可恢复保证。

**第 8–10 课答案要点。** 红色 workflow 先找失败 step，不能用跳过失败来“修绿”。Artifact 不是永久 Release；CI 需要依赖链约束发布。二分需要可判定的好坏端点和一致检查；rebase 后的哈希改变是预期行为。

**第 11–12 课答案要点。** 秘密泄露先轮换；权限按 job 最小化；版本构建可追溯；接手组复现比作者口头保证更有说服力。提醒器五秒测试只覆盖一种入口，正常梯度、周期课表与更新流程都需要独立验收。

**口头抽查题。** 为什么本地提交后同学看不到？为什么 fetch 后文件没变？为什么同一提交说明可能对应不同哈希？为什么删掉 token 文件还要轮换 token？为什么 CI 绿了仍可能没有真机声音？要求学生结合自己的提交记录作答，不背定义。

## 故障排查与命令速查

| 现象 | 先检查 | 合理下一步 |
| --- | --- | --- |
| 找不到 git | 新终端、安装与 PATH | 确认 git --version，避免重复乱装 |
| 推送认证失败 | 账号、remote、凭据权限 | 用正规登录流程刷新凭据，不把 token 粘到命令里 |
| non-fast-forward | fetch 后的分叉图 | 协商 merge 或 rebase，不直接 force main |
| 提示 detached HEAD | 当前指向 tag 或提交 | 想保留工作就先创建分支 |
| 切分支被阻止 | 工作区未提交修改 | 提交或 stash 后再切，不强制覆盖 |
| 合并冲突 | git status 与冲突文件 | 解决、验证、add、完成或 abort |
| workflow 失败 | 首个相关错误与环境 | 重现再修，不隐藏失败 |
| 新电脑无法启动 | README、锁文件、运行时 | 记录缺失步骤并修正文档 |

日常循环：`status → diff → add → diff --cached → commit → fetch → 检查分叉 → push → PR → CI → review`。发生问题时先读状态，不从长命令清单里随机试。命令的范围比记住命令名称更重要。

**自学拓展。** 用 `git worktree` 同时检查两个分支；使用 `git log -S` 或 `-G` 查代码变化；体验 annotated / signed tag；为依赖更新开独立 PR；研究多平台 matrix 和缓存键；设计发布签名与更新校验。每项都应配一个能复现的实验，而不是只在简历里列术语。

## 官方资料与教学维护

下列链接供查证和拓展，不要求学生一次读完。内容按当前官方文档核对，课堂前应复查 GitHub 页面与权限差异。本文示例、实验安排和评分标准为本项目教学设计，不是官方认证课程。

- [Pro Git（可切换中文）](https://git-scm.com/book/en/v2)
- [Git 命令手册](https://git-scm.com/docs)
- [Git Cheat Sheet](https://git-scm.com/cheat-sheet)
- [GitHub 入门](https://docs.github.com/en/get-started)
- [GitHub 身份认证](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/about-authentication-to-github)
- [GitHub Actions workflow 语法](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
- [GitHub Actions 安全使用](https://docs.github.com/en/actions/reference/security/secure-use)
- [git bisect 手册](https://git-scm.com/docs/git-bisect)

教材维护建议：每学期从干净目录跑一遍实验；记录工具版本和环境差异；用 PR 收集学生指出的错误。更改示例命令时同步更新预期结果和评分依据，让教材自身也经过版本控制与审查。
