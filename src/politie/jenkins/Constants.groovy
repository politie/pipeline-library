package politie.jenkins

class Constants {
    // Major version number used by all jobs.
    // Corresponds with the sprint number; update this at the start of each sprint.
    static final MAJOR_VERSION_NUMBER = '1';

    static final SONAR_URL = 'enter sonarqube your url here';
    static final GITLAB_WEB_BASE_URL = 'enter gitlab url here';
    static final GITLAB_API_BASE_URL = 'enter gitlab api url here';
    static final GITLAB_CHECKOUT_BASE_URL = 'enter gitlab checkout url here';
    static final GITLAB_BUILDS_URL = GITLAB_CHECKOUT_BASE_URL + 'builds.git';
    static final GITLAB_VERSION = '8.6';
    static final GITLAB_CREDENTIALS_ID = 'enter credentials here';
    static final GITLAB_API_TOKEN_CREDENTIALS_ID = 'enter api token here';
    static final ENVIRONMENT_DOMAIN_NAME = '.tld'
    static final ENVIRONMENT_TENANT = 'foobar';
}
