# 风格协调方案提示词

工具：built-in image_gen，非CLI。前两轮知识库探索图未作为最终交付；最终图为01-knowledge.png及02-datasets.png。参考图为用户本轮提供的真实界面截图，聊天截图只作为需求背景。所有图片均为待认可提案，不是已实现页面。

## 1. harmonyKnowledgePrompt

Use case: ui-mockup. Generate a complete high-fidelity desktop application screenshot for a Chinese enterprise data agent, a restrained coordinated refinement of supplied app screenshots. This is a design proposal, not marketing art. Single full screen, landscape 1600 x 1000 (or same 8:5 ratio), straight-on crisp UI, no browser chrome, no annotations, no red boxes, no device framing. Readable precise Simplified Chinese typography, practical HTML/CSS design.

Reference 1 shows the newer product. KEEP its excellent folded ribbon purple/blue D logo and '红海 DataAgent' wordmark exactly in character, and outline functional icon family. BUT remove its charcoal buttons, excessive card padding, oversized book tile, huge count, gray inset stats panel, heavy title sizes. Reference 2 shows original colleague style: borrow its calm white and pale lavender, white outlined new conversation button, softly filled pale lavender active nav, thin borders, gentle UI hierarchy. Retain modern clean polish. Do not restore DA square logo or enterprise subtitle.

Design system: pure white main canvas, subtle very light cool gray sidebar #F6F7FA; text #303442, secondary #667085 legible; one restrained violet #6654CE for primary CTA, links and selected state; pale lavender #EEEBFA selected background; border #E5E7EF. No black filled buttons anywhere, no gradients except existing small brand logo, no oversized shadows. Consistent 1.6px outline icons, body 14px, metadata 12px, card title 16px medium, page title 26px semibold. Compact control heights 36px. All look like one mature productivity product.

Layout: sidebar 224px wide full height. Brand at x24 y30, folded logo 28px, wordmark 17px. White outlined button '新建对话' with small compose icon, height38. Nav four items '知识库' selected with soft lavender fill and slim violet left marker, '语义建模', '例行任务', '更多'. Each row40px, icon18px, text14px. Separator then '历史会话' with ellipsis. Today and older section labels, 5 realistic conversation titles in one line truncated, normal weight, no unread dots. Bottom admin user profile and collapse chevron; no subtitle under brand.

Main: breadcrumb topbar height52 with '对话 > 知识库', hairline bottom. Content 32px horizontal and 30px top. Header '知识库', description '组织你的业务数据，让每一次分析都有清晰的依据。' 14px. Right buttons: white outline '数据源管理', violet filled '+ 创建知识库'. At y160 toolbar: search '搜索知识库名称或说明' width300, count '8 个知识库'; right '最近创建' dropdown and grid/list toggle.

At y218 show exactly 8 compact knowledge cards arranged FOUR columns by TWO rows, gap16, each around310px wide and242px tall. No giant empty padding, no internal gray stats rectangle, no mini-dashboard numbers. Every card uses same clear anatomy:
top horizontal row 30px pale lavender book icon, title to its right at16px, ellipsis far right. Name fits one line, truncates if long.
Under top row: short description at13px two lines maximum. Then slim state row small green '已发布 v1' (or amber pending) and discreet gray '可管理' chip. Then compact plain metadata: '2 个数据集 · 创建人 newuser09', below '创建于 2026/11/01'. Creator has NO avatar icon, NO person icon.
Bottom hairline and single footer row: left quiet link '查看数据 →', right '开始提问' as small soft lavender button with chat outline icon; these buttons are pale lavender, NOT solid violet or black.
Cards align across grid with8-10px radius and light border, zero obvious shadow; titles not overly bold.
Example cards and exact appropriate data:
1 我的第一个知识库-981, description '报表访问与用户信息，用于分析平台使用情况。', 已发布 v1,2个数据集,创建人newuser09.
2 音乐业务分析, '播放、订阅与渠道数据，辅助日常业务分析。',已发布v2,6个数据集,创建人liyufan.
3 红海部署验收,'内部验收数据与业务口径。',已发布v1,6个数据集,创建人admin.
4 团队经营分析,'团队共享的经营指标与业务口径。',已发布v1,4个数据集,创建人wangchao.
5 我的第一个知识库-290, '报表访问分析示例。',已发布v1,2个数据集,创建人newuser09.
6 订单分析,'订单与商品数据。',已发布v2,4个数据集,创建人admin.
7 报表使用情况,'按日期与部门分析报表访问情况。',待首次发布,2个数据集,创建人admin. right footer '进入建模' outline softviolet.
8 新建知识库,'上传文件或关联业务表，开始准备数据。',尚未建模,0个数据集,创建人admin. right footer '添加数据' softviolet, no ask button.
At bottom simple muted total '共 8 个知识库' with understated page1/1; no extra modules, no charts, no feature inventions, no marketing, no AI sparkles. Keep comfortable whitespace below grid, consistent proportions with sidebar. Showcase calm compact enterprise polish, high craft, easy reading, coherence. All statistics are design sample data.

## 2. harmonyKnowledgeRefinePrompt

Use case ui-mockup. Refine this knowledge-library screenshot into a MORE COMPACT, softer enterprise UI. Retain exact folded D brand, Chinese content, four-column x two-row8cards, nav, existing capabilities. Same full-screen1600x1000 ratio, no browser chrome.

Crucial corrections: Sidebar width224px rather than260. Maincard grid start y220px. Cardheight exactly240-250px rather than312: first row y220-470; second row y486-736. Leave obvious clean WHITE EMPTY AREA below cards to y930, then simple footer; do not stretch cards to fill screen. Mainpagepadding32. Bookicon container28x28, singlelinecardtitle15px medium, description13px/two lines maximum, metadata12px. Smaller30px pale-lavender askbutton. Cardpadding16. State tags20pxheight in one row. Metadata plain inline "2 个数据集 · newuser09" and next line "创建于 2026/11/01". NO creator/person/avataricon. Compactfooter40px. Thin borders and single hairline abovefooter only. Descriptions and badges close together with8pxgaps. No large empty middle/cardborderpadding. All metadata andcontrols legible, not minuscule.

Color IMPORTANT use muted violet #6654CE for filled CREATE button and links; match old original calm violet, not bright electricblue #5b3df5. No gradients inbuttons. Sidebarselectedbg#EEEBFA, text#6654CE; newchatwhiteoutline, askbuttons#F2EFFB bg#6654CE text. Body#303442, secondary#667085, pagepurewhite. No blackfilledbuttons, noinventedAIart. Font Chinese natural PingFang/MicrosoftYaHei style, avoid overlyboldheaders. Pageheading26px, sidemenu14px, 38pxrows.
Keep only four navitems知识库/语义建模/例行任务/更多. Clear light borders. Do not add any modules/features. Screen should feel calm, tight, readable and coherent. No dashboard countpanels. Preserve greenpublished/amberpending statusesandrightfooteractionbasedonstate. This is finalproposalimage forreview.

## 3. harmonyKnowledgeRestorePrompt

Use case ui-mockup. FINAL quality correction of this provided proposed screenshot: keep exact mainpage grid layout, 4x2 cards, content, compactinformation, foldedlogo, no darkfilledbuttons. Restore complete realistic sidebar from existing product. Sidebar is224px wide at1600pxcanvas; overall product as1600x1000 screenshot no browserchrome. It MUST contain below four navigationitems a hairline at y360, "历史会话" and ellipsis at y390, section "今天" at y430, three sessiontitles at y460, y500,y540 ("最近一个月每天的报表访问次数", "各部门报表使用情况", "本月访问趋势分析"), section "更早"at y590, two sessiontitlesaty625and665("平台使用情况汇总", "订单金额趋势分析"). Eachoneone line truncatedinside sidebar, font13pxgray, no statusdots. At sidebarbottom y930 separator, then lavender circular A avatar + admin + 全局管理员 textandcollapsechevron. Do NOT leave sidebar blank.

Make violet restrained #6654CE, less electricblue saturation, absolutely flat fills, no buttongradients. All originalbookicons remain. Purewhite maincanvas. Typography font14pxbody,16pxcardtitle,26pxpagetitle, allnaturalChinese. To ensurecardcompactness gridtop220, firstrowends470, secondrowstarts486 ends736; blankwhitebelow. Footer total andpagecontrols y790, notpinnedbottom. DO NOT stretchcards. Description2linesmin reserved40px, state20pxheight, metadata2linesat12px,30pxfooterbuttons. Good legibility. Current screen is design reference, keep complete affordances and sidebar, no marketing labels.

## 4. harmonyDatasetPrompt

Use case ui-mockup. Generate the second coordinated full-desktop screenshot of an EXISTING Chinese enterprise dataagent product. Complete knowledge-library DETAIL / DATASETS page, same calm light design as reference1, using original colleague screenshot reference2 for restrained proportions. Full1600x1000 8:5aspect, no browser chrome/deviceframe/annotations. Purewhite UI, pixelsharp readable SimplifiedChinese.

Reference1 sets new folded purple/blue D brand and wordmark "红海 DataAgent", iconfamily, compactknowledgecardsandsoftnav. Reference2 supplies real functions and sampledata. DO NOT restore DA logo, subtitle, blackbuttons, arbitrary navigation, marketing sections, fakeAIfeatures, large panels or invisibletext. Reusable practicalHTML/CSS.

Design tokens: sidebar224px #F6F7FA, mainpurewhite, text#303442, secondary#667085, muted restrainedviolet#6654CE primaryfilledbutton&links, pale lavender#EEEBFA selectednav, border#E5E7EF,8pxcornerradius, noheavydropshadow, no buttongradients, NO blackfilledbuttons. Body14px, metadata12px, title24px600, controls36pxheight, icon18px1.6pxstrokes.
Sidebar exactlycomplete: foldedbrandtop, whiteoutline新建对话composebutton, four nav知识库selected(softlavenderfill&violetleftmarker)/语义建模/例行任务/更多. Separator+历史会话ellipsis, 今天+threeone-lineconversationtitles, 更早+two titles. NO unreadpurpledots. BottomadminprofilewithcircularAand全局管理员+collapsechevron. Retainnoenterprisebrandsubtitle. This sidebarMUST matchreference1.

Mainbreadcrumb topbar52px "对话 > 知识库 > 我的第一个知识库-981". Mainpadding32px. At y90 mainheading"我的第一个知识库-981" 24pxsemibold, NO64pxiconblock, nooversizedtitle. Under at y130plainmetadata "2 个数据集 · 创建于 2026/11/01 · 添加业务说明" lastquietvioletlink. Toprightwhiteoutline"成员与权限", whiteoutline"设置⌄" (containsalreadyexisting编辑信息and事实表时间范围, notnewfeatures), singleprimaryvioletfilled "基于此库提问". No blackcontrols.

At y180 thin neutralsoftgray statusbarheight44px withsmallgreencheckthen "已发布 v1", thinverticalseparator, "可直接提问，业务口径可继续完善。" inlegiblegray. Rightquietviolettext"完善业务模型 →". Thinborder,6pxcornerradius. NOT ahero/statuscard. No giantgreenicon,no oversizedbold.
At y251 horizontal tabs "数据集 2"activevioletwith2pxunderline and "语义建模"gray. Samevioletasnav. Hairlineacrosscontent.
At y300 a VERY SLIM completedupload summaryneutralbgheight32px: smallgreencheck "文件已全部导入" and "已完成 2/2" muted, far right smallchevron. No purpleprogressbar stretchingpage. Preserveexistinguploadsummaryability.
At y360 toolbarsearchwidth300height36 "搜索数据集"; rightwhiteoutline"关联数据源"databaseicon +whiteoutline"上传文件"uploadicon. NO blackuploadbutton.
At y415 bordered data tablewidthfullwith8pxroundcorners:
header40pxsoftgray withcolumns "数据集" (25% width), "数据来源"(35%), "行数"(12%, centered), "字段数"(10%,centered),"操作"(18%).
Eachdatarow64px nothuge, no oversizediconpadding or nestedboxinsidecells.
row1 smallneutraloutlinedtableiconand"报表访问明细"medium14px; datasourcefileoutlineiconwithtwo-line"文件上传"14px then "报表访问明细.xlsx"12px muted; rowcount98,819center,fields6center; rightquietviolet"查看数据 →"thenellipsis.
row2 iconand"用户列表"; source"文件上传"and"用户列表.xlsx";2,046center,5center; "查看数据 →"andellipsis.
tablefooter40px, "共 2 个数据集" left, smallinfoicon+"点击名称查看数据与字段"grayright.
Belowtable plenty of cleanwhiteworkspace (no extra dashboards, KPIcards, suggestedactions or marketing). Intentional productivity proportion, lightbreathing space without tallhero/cardpadding. All UItextclear. This is a coordinated soft compact refinement from the actual productscreenshots, all functionalityandpermissionboundaryunchanged.
