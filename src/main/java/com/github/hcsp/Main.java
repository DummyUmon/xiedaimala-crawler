package com.github.hcsp;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.stream.Collectors;

public class Main {
    private static final String USER_NAME = "root";
    private static final String PASSWORD = "root";


    private static String getNextLink(Connection connection, String sql) throws SQLException {
        ResultSet resultSet = null;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            resultSet = statement.executeQuery();
            while (resultSet.next()) {
                return (resultSet.getString(1));
            }
        } finally {
            if (resultSet != null) {
                resultSet.close();
            }
        }
        return null;
    }

    private static String getNextLinkThenDelete(Connection connection) throws SQLException {
        String link = getNextLink(connection, "select link from LINKS_TO_BE_PROCESSED LIMIT1");
        if (link != null) {
            updateDatabase(connection, link, "DELETE FROM LINKS_TO_BE_PROCESSED where link=?");
        }
        return link;
    }

    @SuppressFBWarnings("DMI_CONSTANT_DB_PASSWORD")
    public static void main(String[] args) throws IOException, SQLException, ClassNotFoundException {
        Connection connection = DriverManager.getConnection("jdbc:h2:file:C:/Users/UMon/IdeaProjects/tmp/xiedaimala-crawler/news", USER_NAME, PASSWORD);

        String link;

        // 从数据库中加载下一个链接，如果能加载到，则进行循环
        while ((link = getNextLinkThenDelete(connection)) != null) {
            //询问数据库，当前连接是不是已经竟被处理过了？
            if (isLinkProcessed(connection, link)) {
                continue;
            }

            if (isInterestingLink(link)) {
                System.out.println(link);

                Document doc = httpGetAndParseHtml(link);

                parseUrlsFromPagesAndStoreIntoDatabase(connection, doc);
                //假如这是一个新闻的详情页面，就存入数据库，否则，就什么都不做
                storeIntoDatabaseIfItIsNewsPage(connection, doc, link);

                updateDatabase(connection, link, "INSERT INTO LINKS_ALREADY_PROCESSED (link) values(?)");
            }
        }
    }

    private static void parseUrlsFromPagesAndStoreIntoDatabase(Connection connection, Document doc) throws SQLException {
        for (Element aTag : doc.select("a")) {
            String href = aTag.attr("href");

            if (href.startsWith("//")) {
                href = "https" + href;
            }
            if (!href.toLowerCase().startsWith("javascript")) {
                updateDatabase(connection, href, "INSERT INTO LINKS_TO_BE_PROCESSED (link) values(?)");
            }
        }
    }

    @SuppressFBWarnings("DMI_CONSTANT_DB_PASSWORD")
    private static boolean isLinkProcessed(Connection connection, String link) throws SQLException {
        ResultSet resultSet = null;
        try (PreparedStatement statement = connection.prepareStatement("SELECT LINK from LINKS_ALREADY_PROCESSED where link = ?");) {
            statement.setString(1, link);
            resultSet = statement.executeQuery();
            while (resultSet.next()) {
                return true;
            }
        } finally {
            if (resultSet != null) {
                resultSet.close();
            }
            return false;
        }
    }

    private static void updateDatabase(Connection connection, String link, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, link);
            statement.executeUpdate();
        }
    }


    private static void storeIntoDatabaseIfItIsNewsPage(Connection connection, Document doc, String link) throws SQLException {
        ArrayList<Element> articleTags = doc.select("article");
        if (!articleTags.isEmpty()) {
            for (Element articleTag : articleTags) {
                String title = articleTags.get(0).child(0).text();
                String content = articleTag.select("p").stream().map(Element::text).collect(Collectors.joining("\n"));

                try (PreparedStatement statement = connection.prepareStatement("insert into news (url,title,content,created_at,modified_at)values ( ?,?,?,now(),now() )")) {
                    statement.setString(1, link);
                    statement.setString(2, title);
                    statement.setString(3, content);
                    statement.executeUpdate();

                }
            }
        }
    }

    private static Document httpGetAndParseHtml(String link) throws IOException {
        //这是我们感兴趣的，我们只处理新浪站内的链接
        CloseableHttpClient httpclient = HttpClients.createDefault();

        HttpGet httpGet = new HttpGet(link);
        httpGet.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/101.0.4951.54 Safari/537.36");

        try (CloseableHttpResponse response1 = httpclient.execute(httpGet)) {
            HttpEntity entity1 = response1.getEntity();
            String html = EntityUtils.toString(entity1);
            return Jsoup.parse(html);
        }
    }

    //我们只关心news。sina的我们要排除登陆页面
    private static boolean isInterestingLink(String link) {
        return (isNewsPage(link) || isIndexPage(link)) && isNotLoginPage(link);
    }

    private static boolean isIndexPage(String link) {
        return "https://sina.cn".equals(link);
    }

    private static boolean isNewsPage(String link) {
        return link.contains("news.sina.cn");
    }

    private static boolean isNotLoginPage(String link) {
        return !link.contains("passport.sina.cn");
    }
}