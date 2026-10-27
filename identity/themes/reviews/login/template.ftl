<#--
  The page around every sign-in and registration form: login.html's nav bar,
  centred card and footer. Adapted from the base theme's template.ftl, whose
  scripts and nested sections ("header", "form", "info", ...) it keeps.
-->
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html class="${properties.kcHtmlClass!}" lang="${lang}">

<head>
    <meta charset="utf-8">
    <#if properties.meta?has_content>
        <#list properties.meta?split(' ') as meta>
            <meta name="${meta?split('==')[0]}" content="${meta?split('==')[1]}"/>
        </#list>
    </#if>
    <title>${msg("loginTitle", (realm.displayName!''))}</title>
    <link rel="icon" type="image/svg+xml" href="${url.resourcesPath}/img/favicon.svg" />
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <script type="importmap">
        {
            "imports": {
                "rfc4648": "${url.resourcesCommonPath}/vendor/rfc4648/rfc4648.js"
            }
        }
    </script>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="module">
        <#outputformat "JavaScript">
        import { startSessionPolling } from ${(url.resourcesPath + "/js/authChecker.js")?c};

        startSessionPolling(
            ${url.ssoLoginInOtherTabsUrl?c}
        );
        </#outputformat>
    </script>
    <#if authenticationSession??>
        <script type="module">
            <#outputformat "JavaScript">
            import { checkAuthSession } from ${(url.resourcesPath + "/js/authChecker.js")?c};

            checkAuthSession(
                ${authenticationSession.authSessionIdHash?c}
            );
            </#outputformat>
        </script>
    </#if>
</head>

<body class="${properties.kcBodyClass!}" data-page-id="login-${pageId}">
    <nav class="mx-auto flex w-full max-w-6xl items-center justify-between px-4 py-4 xl:px-0">
        <div class="leading-loose">
            <#if client?? && client.baseUrl?has_content>
                <a href="${client.baseUrl}" class="text-3xl font-bold">${realm.displayName!''}</a>
            <#else>
                <span class="text-3xl font-bold">${realm.displayName!''}</span>
            </#if>
        </div>
        <div class="leading-loose">
            <#if pageId != "login" && url.loginUrl?has_content>
                <a href="${url.loginUrl}" class="px-3 text-sm font-semibold text-blue-500">${msg("doLogIn")}</a>
            </#if>
            <#if pageId != "register" && realm.registrationAllowed && url.registrationUrl?has_content>
                <a href="${url.registrationUrl}" class="px-3 text-sm font-semibold text-blue-500">${msg("doRegister")}</a>
            </#if>
        </div>
    </nav>

    <main class="mb-48 flex flex-1 flex-col justify-center px-6 py-12">
        <h2 id="kc-page-title" class="mt-10 text-center text-2xl font-bold leading-9 tracking-tight text-gray-900">
            <#nested "header">
        </h2>

        <div id="kc-content" class="mx-auto mt-10 w-full max-w-md rounded-lg p-6 shadow-2xl">
            <#if auth?has_content && auth.showUsername() && !auth.showResetCredentials()>
                <#nested "show-username">
                <div id="kc-username" class="mb-6 flex items-center justify-between text-sm text-gray-700">
                    <span id="kc-attempted-username" class="font-semibold">${auth.attemptedUsername}</span>
                    <a id="reset-login" href="${url.loginRestartFlowUrl}">${msg("restartLoginTooltip")}</a>
                </div>
            </#if>

            <#-- App-initiated actions should not see warning messages about the need to complete the action during login. -->
            <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                <#assign tone = {"error": "bg-red-50 text-red-700 ring-red-200", "warning": "bg-amber-50 text-amber-800 ring-amber-200",
                                 "success": "bg-green-50 text-green-800 ring-green-200", "info": "bg-blue-50 text-blue-800 ring-blue-200"}>
                <div role="alert" class="mb-6 rounded-md px-3 py-2 text-sm ring-1 ring-inset ${tone[message.type]!tone.info}">
                    ${kcSanitize(message.summary)?no_esc}
                </div>
            </#if>

            <#nested "form">

            <#if auth?has_content && auth.showTryAnotherWayLink()>
                <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post" class="mt-6 text-sm">
                    <input type="hidden" name="tryAnotherWay" value="on"/>
                    <a href="#" id="try-another-way"
                       onclick="document.forms['kc-select-try-another-way-form'].requestSubmit();return false;">${msg("doTryAnotherWay")}</a>
                </form>
            </#if>

            <#nested "socialProviders">

            <#if displayInfo>
                <div id="kc-info" class="${properties.kcSignUpClass!}">
                    <#nested "info">
                </div>
            </#if>
        </div>
    </main>

    <footer class="mt-10 flex h-40 bg-gray-800 py-5">
        <div class="mx-auto w-full max-w-6xl px-4 xl:px-0">
            <div class="mb-4 text-sm text-white">Acme Product Reviews</div>
            <div class="my-4 text-sm text-white">Portfolio Demo</div>
        </div>
    </footer>
</body>
</html>
</#macro>
