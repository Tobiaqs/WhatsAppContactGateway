package tobiass.whatsappcontactgateway;

/**
 * this is for people who don't like sharing their entire contact db with whatsapp,
 * and occasionally want to start a whatsapp conversation with one of their contacts.
 *
 * @author Tobiaqs
 */

import android.app.Activity;
import android.app.LoaderManager;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.AdapterView;
import android.widget.FilterQueryProvider;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.SimpleCursorAdapter;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity  implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener, FilterQueryProvider, SearchView.OnQueryTextListener {
    //
    private SimpleCursorAdapter mAdapter;

    // stuff we want from the contact db
    private static final String[] PROJECTION = {
            ContactsContract.Contacts._ID, // necessary for looking up phone numbers + contact URI
            ContactsContract.Contacts.LOOKUP_KEY, // necessary for looking up contact URI
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY // necessary for list + search
    };

    // keep track of indices in the projection
    private static final int CONTACT_ID_INDEX = 0;
    private static final int CONTACT_KEY_INDEX = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // create simple cursor adapter that shows DISPLAY_NAME_PRIMARY in list items
        mAdapter = new SimpleCursorAdapter(this, android.R.layout.simple_list_item_1,
                null, new String[] { ContactsContract.Contacts.DISPLAY_NAME_PRIMARY },
                new int[] { android.R.id.text1 }, 0);

        // hook up the data source through a filter provider (not the most elegant way, I know)
        mAdapter.setFilterQueryProvider(this);

        // find the listView
        ListView listView = findViewById(R.id.contact_list);

        // set adapter to the listview to make it view contacts
        listView.setAdapter(mAdapter);

        // connect our click and long click listeners
        listView.setOnItemClickListener(this);
        listView.setOnItemLongClickListener(this);

        // invoke filter, i.e. prepare a data set for the adapter to show
        mAdapter.getFilter().filter(null);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // inflate menu with searchview
        getMenuInflater().inflate(R.menu.main_toolbar_menu, menu);

        // find searchview
        SearchView search = (SearchView) menu.findItem(R.id.search).getActionView();

        // set a query text listener to the searchview
        search.setOnQueryTextListener(this);

        return super.onCreateOptionsMenu(menu);
    }

    private String getWhatsAppPkgName() {
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> pkgAppsList = getPackageManager().queryIntentActivities( mainIntent, 0);
        for (ResolveInfo info : pkgAppsList) {
            if (info.loadLabel(getPackageManager()).equals("WhatsApp")) {
                return info.activityInfo.packageName;
            }
        }

        return null;
    }

    private String postProcessNumber(String number) {
        // do some postprocessing
        number = number.replace("-", "")
                .replace(" ", "")
                .replace("+", "00");

        if (!number.startsWith("00")) {
            number = "31" + number.substring(1);
        } else {
            number = number.substring(2);
        }

        return number;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        // get the cursor
        Cursor cursor = mAdapter.getCursor();

        // move it to the position of the clicked item
        cursor.moveToPosition(position);

        // get contact id
        long contactId = cursor.getLong(CONTACT_ID_INDEX);

        // get CR to be able to query
        ContentResolver cr = getContentResolver();

        // query phone number database
        Cursor phones = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", new String[] { String.valueOf(contactId) }, null);

        // keep track of found phone numbers
        String lastFoundNumber = null;
        String mobileNumber = null;

        // iterate phone numbers that belong to this contact
        while (phones.moveToNext()) {
            // get actual number
            String number = phones.getString(
                    phones.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));

            // check the associated type
            int type = phones.getInt(phones.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE));

            // type = mobile?
            if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                // we've found ourselves a mobile number
                mobileNumber = number;
            } else {
                // otherwise store the number anyway
                lastFoundNumber = number;
            }
        }

        // close cursor to avoid memory leaks
        phones.close();

        // this will hold the "best match"
        String number = null;

        // decide "best match"
        if (mobileNumber != null) {
            number = mobileNumber;
        } else if (lastFoundNumber != null) {
            number = lastFoundNumber;
        }

        // match found?
        if (number != null) {
            String pkg = getWhatsAppPkgName();

            if (pkg == null) {
                // if no match found, let the user know
                Toast.makeText(this, "No WhatsApp found!", Toast.LENGTH_SHORT).show();
                return;
            }

            // open up whatsapp via an intent
            Uri uri = Uri.parse("https://api.whatsapp.com/send?phone=" + postProcessNumber(number));

            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.setPackage(pkg);
            startActivity(intent);
        } else {
            // if no match found, let the user know
            Toast.makeText(this, "No number found!", Toast.LENGTH_SHORT).show();
        }

    }

    // open up contact details when items are long pressed
    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        Cursor cursor = mAdapter.getCursor();
        cursor.moveToPosition(position);
        long contactId = cursor.getLong(CONTACT_ID_INDEX);
        String lookupKey = cursor.getString(CONTACT_KEY_INDEX);
        Uri uri = ContactsContract.Contacts.getLookupUri(contactId, lookupKey);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        startActivity(intent);
        return true;
    }

    // the method that actually queries the contact db and also filters
    @Override
    public Cursor runQuery(CharSequence constraint) {
        ContentResolver cr = getContentResolver();
        String selection = null;
        String[] selectionArgs = null;
        if (constraint != null) {
            selection = "LOWER(" + ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + ") LIKE ?";
            selectionArgs = new String[] { "%" + constraint.toString().toLowerCase() + "%" };
        }

        return cr.query(ContactsContract.Contacts.CONTENT_URI,
                PROJECTION,
                selection,
                selectionArgs,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC");
    }

    // don't do anything when search button is hit
    @Override
    public boolean onQueryTextSubmit(String query) {
        return false;
    }

    // filter realtime as user types in name in searchview
    @Override
    public boolean onQueryTextChange(String query) {
        if (query.equals("")) {
            mAdapter.getFilter().filter(null);
        } else {
            mAdapter.getFilter().filter(query);
        }
        return true;
    }
}
